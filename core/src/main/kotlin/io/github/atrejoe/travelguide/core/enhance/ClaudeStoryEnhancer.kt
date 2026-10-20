package io.github.atrejoe.travelguide.core.enhance

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import io.github.atrejoe.travelguide.core.motion.TravelMode
import kotlinx.coroutines.future.await
import java.time.Duration
import java.util.Locale

/** A Claude model to choose in the settings, with its price in dollars per million tokens. */
enum class ClaudeModel(val id: String, val label: String, val inputUsdPerMTok: Double, val outputUsdPerMTok: Double) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", 4.0, 20.0),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.0, 10.0),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50),
    ;

    companion object {
        val DEFAULT = OPUS
        fun byId(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Retells stories with Claude, using the listener's own API key (bring your own key). The key
 * only ever goes to the Anthropic API.
 */
class ClaudeStoryEnhancer(
    private val client: AnthropicClient,
    private val model: ClaudeModel = ClaudeModel.DEFAULT,
) : StoryEnhancer {

    constructor(apiKey: String, model: ClaudeModel = ClaudeModel.DEFAULT) : this(
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(30))
            // The guide tells the template story rather than wait for retries.
            .maxRetries(1)
            .build(),
        model,
    )

    /** The whole answer, thinking included, at most [MAX_TOKENS]; the prompt is a few thousand tokens. */
    override val maxCostUsd: Double = cost(MAX_INPUT_TOKENS, MAX_TOKENS)

    override suspend fun retell(request: StoryRequest): RetoldStory {
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(MAX_TOKENS)
            // Retelling a short text needs little thinking, which is billed as output.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(SYSTEM_PROMPT)
            .addUserMessage(prompt(request))
            .build()
        val message = client.async().messages().create(params).await()
        val cost = cost(message.usage().inputTokens(), message.usage().outputTokens())
        return RetoldStory(text(message), cost)
    }

    private fun text(message: Message): String {
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) throw IllegalStateException("Claude declined to tell this story")
        if (stop == StopReason.MAX_TOKENS) throw IllegalStateException("The story was cut off")
        val text = message.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }.trim()
        if (text.isEmpty()) throw IllegalStateException("Claude returned no story")
        return text
    }

    private fun cost(inputTokens: Long, outputTokens: Long): Double =
        (inputTokens * model.inputUsdPerMTok + outputTokens * model.outputUsdPerMTok) / 1_000_000

    companion object {
        private const val MAX_TOKENS = 2_000L
        private const val MAX_INPUT_TOKENS = 3_000L

        internal val SYSTEM_PROMPT = """
            You are a warm, knowledgeable travel guide talking to a traveller through their earphones.
            You retell the story of a place they are passing, from the source text you are given.

            - Use only facts from the source text. Never add names, dates or numbers that are not in it.
            - Pick what is surprising, human or dramatic, and leave out dry lists of facts.
            - Write for the ear: plain sentences of moderate length, no lists, headings, markdown,
              brackets, abbreviations or URLs. Write numbers and years as they are normally read.
            - Do not greet the traveller, do not repeat the opening sentence, and do not mention
              Wikipedia, the source text or yourself.
            - Answer with the story only.
        """.trimIndent()

        internal fun prompt(request: StoryRequest): String {
            val travelling = when (request.mode) {
                TravelMode.WALKING -> "walking, so they can take a good look"
                TravelMode.CYCLING -> "cycling past"
                TravelMode.DRIVING -> "driving past, eyes on the road"
                TravelMode.TRAIN -> "looking out of a train window"
                TravelMode.FLYING -> "flying over, high above"
                TravelMode.STATIONARY -> "standing still"
            }
            val language = Locale.forLanguageTag(request.lang).getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
            return buildString {
                appendLine("Place: ${request.title}")
                request.description?.let { appendLine("What it is: $it") }
                appendLine("Where: ${request.where}")
                appendLine("The traveller is $travelling.")
                if (request.interests.isNotEmpty()) {
                    appendLine("They are most interested in: ${request.interests.joinToString { it.name.lowercase() }}.")
                }
                appendLine("The guide has just said: \"${request.opener}\"")
                appendLine()
                appendLine("Source text:")
                appendLine("<source>")
                appendLine(request.source)
                appendLine("</source>")
                appendLine()
                append("Continue with the story of this place in about ${request.sentences} sentences, in $language.")
            }
        }
    }
}
