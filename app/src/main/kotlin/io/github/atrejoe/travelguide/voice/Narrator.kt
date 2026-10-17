package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.media.AudioAttributes
import io.github.atrejoe.travelguide.settings.AppSettings
import java.util.Locale

/** Speaks one sentence at a time so playback can be paused and resumed at sentence boundaries. */
interface Narrator {
    /**
     * Speaks [text] and suspends until it has been played. Returns false when interrupted by
     * [stop] or when speaking failed.
     */
    suspend fun speak(text: String): Boolean

    /** Interrupts the sentence currently being spoken. */
    fun stop()

    fun release()
}

object Narrators {
    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Creates the narrator for the configured voice, falling back to the system voice when the
     * chosen neural voice has not been downloaded yet.
     */
    fun create(context: Context, settings: AppSettings, models: VoiceModelManager): Narrator {
        VoiceCatalog.neural(settings.voiceId)?.let { voice ->
            models.model(voice)?.let { return NeuralNarrator(it, settings.pitch, settings.rate) }
        }
        return SystemTtsNarrator(
            context,
            voiceName = VoiceCatalog.systemVoiceName(settings.voiceId)?.takeIf { it.isNotEmpty() },
            pitch = settings.pitch,
            rate = settings.rate,
            locale = Locale.UK,
        )
    }
}
