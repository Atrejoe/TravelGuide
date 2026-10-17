package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import io.github.atrejoe.travelguide.settings.AppSettings
import java.util.Locale

/** Speaks one sentence at a time so playback can be paused and resumed at sentence boundaries. */
interface Narrator {
    /** Human-readable name of the voice actually in use. */
    val label: String

    /**
     * Speaks [text] and suspends until it has been played. [upcoming] is the sentence that will
     * follow, so voices that synthesise ahead can start on it while this one plays. Returns false
     * when interrupted by [stop] or when speaking failed.
     */
    suspend fun speak(text: String, upcoming: String? = null): Boolean

    /** Interrupts the sentence currently being spoken. */
    fun stop()

    fun release()
}

/** The narrator to use, plus why it is not the one the user picked, if that is the case. */
data class NarratorChoice(val narrator: Narrator, val warning: String?)

object Narrators {
    private const val TAG = "Narrators"

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Creates and warms up the narrator for the configured voice. When the chosen on-device voice
     * is missing or cannot be loaded we fall back to the system voice, and say so: a silent
     * fallback once made a male voice sound like a robotic female one.
     */
    suspend fun create(context: Context, settings: AppSettings, models: VoiceModelManager): NarratorChoice {
        var warning: String? = null
        val neural = VoiceCatalog.neural(settings.voiceId)
        if (neural != null) {
            val files = models.files(neural.pkg)
            if (files == null) {
                warning = "${neural.name} is not downloaded yet, so the system voice is used."
            } else {
                val narrator = NeuralNarrator(neural, files, settings.pitch, settings.rate)
                try {
                    narrator.warmUp()
                    return NarratorChoice(narrator, null)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not load ${neural.id}", t)
                    narrator.release()
                    warning = "${neural.name} could not be loaded (${t.message ?: t.javaClass.simpleName}), so the system voice is used."
                }
            }
        } else if (settings.voiceId.startsWith(VoiceCatalog.NEURAL_PREFIX)) {
            warning = "The chosen voice is no longer available; pick another one in Settings."
        }
        val system = SystemTtsNarrator(
            context,
            voiceName = VoiceCatalog.systemVoiceName(settings.voiceId)?.takeIf { it.isNotEmpty() },
            pitch = settings.pitch,
            rate = settings.rate,
            locale = Locale.UK,
        )
        val systemWarning = system.initialize()
        return NarratorChoice(system, warning ?: systemWarning)
    }
}
