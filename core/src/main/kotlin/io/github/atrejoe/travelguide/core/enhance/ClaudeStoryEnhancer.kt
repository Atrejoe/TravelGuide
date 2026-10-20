package io.github.atrejoe.travelguide.core.enhance

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.future.await
import java.time.Duration

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
    override val id = "claude"

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
            .system(StoryPrompt.SYSTEM)
            .addUserMessage(StoryPrompt.user(request))
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
    }
}
