package io.github.atrejoe.travelguide.story

import android.content.Context
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.LocalModel
import io.github.atrejoe.travelguide.core.enhance.LocalStoryEnhancer
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
        val llama = LlamaModel.load(context, file, model.name)
        try {
            val loadSeconds = loadStart.elapsedNow().inWholeMilliseconds / 1000.0
            onStatus("Loaded in ${seconds(loadSeconds)}. Retelling the sample story…")
            val story = LocalStoryEnhancer(llama, model).retell(LocalStoryEnhancer.SAMPLE)
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
}
