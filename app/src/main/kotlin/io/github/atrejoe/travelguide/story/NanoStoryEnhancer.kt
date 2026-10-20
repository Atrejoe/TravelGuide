package io.github.atrejoe.travelguide.story

import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import io.github.atrejoe.travelguide.core.enhance.RetoldStory
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.StoryPrompt
import io.github.atrejoe.travelguide.core.enhance.StoryRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Retells stories with Gemini Nano, on the phone itself through Android AICore (issue #7): free,
 * and nothing leaves the phone. Only some recent phones have it; see [status].
 */
class NanoStoryEnhancer : StoryEnhancer, AutoCloseable {
    override val id = "nano"
    override val maxCostUsd = 0.0

    private val model: GenerativeModel = Generation.getClient()

    override suspend fun retell(request: StoryRequest): RetoldStory {
        check(model.checkStatus() == FeatureStatus.AVAILABLE) { "Gemini Nano is not available" }
        val response = model.generateContent(
            generateContentRequest(SystemInstruction(StoryPrompt.SYSTEM), TextPart(StoryPrompt.user(request))) {
                temperature = 0.4f
                topK = 16
                maxOutputTokens = MAX_OUTPUT_TOKENS
            },
        )
        val candidate = response.candidates.firstOrNull() ?: error("Gemini Nano returned no story")
        val text = if (candidate.finishReason == Candidate.FinishReason.MAX_TOKENS) {
            StoryPrompt.completeSentences(candidate.text) ?: error("The story was cut off")
        } else {
            candidate.text.trim()
        }
        check(text.isNotEmpty()) { "Gemini Nano returned no story" }
        return RetoldStory(text, 0.0)
    }

    override fun close() {
        model.close()
    }

    /** Whether Gemini Nano can be used here, or first needs downloading. */
    enum class Availability { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 256

        /** AICore, and with it Gemini Nano, only exists on recent Android versions. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        suspend fun status(): Availability {
            if (!supported) return Availability.UNAVAILABLE
            val model = Generation.getClient()
            return try {
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> Availability.AVAILABLE
                    FeatureStatus.DOWNLOADABLE -> Availability.DOWNLOADABLE
                    FeatureStatus.DOWNLOADING -> Availability.DOWNLOADING
                    else -> Availability.UNAVAILABLE
                }
            } catch (e: Exception) {
                Availability.UNAVAILABLE
            } finally {
                model.close()
            }
        }

        /** Downloads Gemini Nano through AICore; collect the flow to follow it. */
        fun download(): Flow<DownloadStatus> {
            val model = Generation.getClient()
            return model.download().onCompletion { model.close() }
        }
    }
}
