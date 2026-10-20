package io.github.atrejoe.travelguide.story

import android.content.Context
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.LocalModel
import io.github.atrejoe.travelguide.core.enhance.LocalStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.StoryRequest
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import java.util.Locale
import kotlin.time.TimeSource

/**
 * Retells a sample story with an open model, to show how fast this phone runs it (issue #51):
 * how quickly the model reads the prompt and writes the story, and the story itself.
 */
object LocalStoryBenchmark {
    val supported: Boolean get() = LlamaEngine.supported

    suspend fun run(context: Context, model: LocalModel, onStatus: (String) -> Unit): String {
        val file = context.app.localModels.file(model) ?: error("${model.name} is not downloaded")
        val loadStart = TimeSource.Monotonic.markNow()
        val llama = LlamaModel.load(context, file)
        try {
            val loadSeconds = loadStart.elapsedNow().inWholeMilliseconds / 1000.0
            onStatus("Loaded in ${seconds(loadSeconds)}. Retelling the sample story…")
            val story = LocalStoryEnhancer(llama, model).retell(SAMPLE)
            val stats = llama.lastStats ?: error("No timings")
            return buildString {
                appendLine("${model.name}, ${LlamaEngine.threads()} threads:")
                appendLine("• Loading: ${seconds(loadSeconds)}")
                appendLine("• Reading the prompt: ${stats.promptTokens} tokens in ${seconds(stats.promptSeconds)} (${perSecond(stats.readPerSecond)})")
                appendLine("• Writing the story: ${stats.storyTokens} tokens in ${seconds(stats.storySeconds)} (${perSecond(stats.writePerSecond)})")
                appendLine("• A story takes about ${seconds(stats.promptSeconds + stats.storySeconds)} here.")
                appendLine()
                append(story.text)
            }
        } finally {
            llama.close()
        }
    }

    private fun seconds(s: Double) = String.format(Locale.ROOT, "%.1f s", s)
    private fun perSecond(t: Double) = String.format(Locale.ROOT, "%.1f tokens/s", t)

    /** About as long as a real request: the template's highlights and the article's lead. */
    private val SAMPLE = StoryRequest(
        title = "Grote Kerk, Haarlem",
        description = "Protestant church in Haarlem, Netherlands",
        source = "The Grote Kerk or Sint-Bavokerk is a Reformed Protestant church and former Catholic cathedral on " +
            "the central market square in Haarlem. It is dedicated to Saint Bavo. Building began around 1370 on the " +
            "site of an earlier church that had been damaged by fire, and the church was finished around 1520. " +
            "After the Reformation it became a Protestant church in 1578, and its altars and images were removed. " +
            "The church is famous for its organ, built by Christian Müller between 1735 and 1738. With its tall case " +
            "and thousands of pipes it was for a time the largest organ in the world. Many famous musicians came to " +
            "play it: George Frideric Handel played it in 1740, and a ten-year-old Wolfgang Amadeus Mozart played it " +
            "in 1766 during his family's tour of Europe. The painter Frans Hals is buried in the church, and a lamp " +
            "marks his grave. Small houses and shops were built against the outside walls of the church over the " +
            "centuries; the rent paid for its upkeep, and some of them are still there. Inside, the floor is made of " +
            "gravestones, and the church was used as a burial place until the nineteenth century. Dogs were driven " +
            "out of the church by a dog whipper, and a chapel still shows a carving of one. Today the church hosts " +
            "concerts and the International Organ Festival Haarlem, held every two years since 1951.",
        opener = "On your left rises the Grote Kerk, the church of Saint Bavo.",
        mode = TravelMode.WALKING,
        where = "left · 100 m",
        interests = listOf(Interest.HISTORY, Interest.CULTURE),
        sentences = 5,
        lang = "en",
    )
}
