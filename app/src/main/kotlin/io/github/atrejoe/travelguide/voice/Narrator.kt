package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import io.github.atrejoe.travelguide.core.story.SpeechChunker
import io.github.atrejoe.travelguide.settings.AppSettings
import java.util.Locale

/** Speaks one sentence at a time so playback can be paused and resumed at sentence boundaries. */
interface Narrator {
    /** Human-readable name of the voice actually in use. */
    val label: String

    /**
     * True when speech is rendered on the phone, which takes a while. Stories are then handed over
     * in short pieces (see [Narrators.pieces]) and rendered ahead with [prepare].
     */
    val rendersOnDevice: Boolean get() = false

    /**
     * Seconds of rendering per second of speech, once measured. Above 1 the voice can't keep up
     * and there will be pauses between sentences.
     */
    val realTimeFactor: Double? get() = null

    /** Starts rendering [texts] in order, before they are spoken; drops anything else still queued. */
    fun prepare(texts: List<String>) = Unit

    /**
     * Speaks [text] and suspends until it has been played. Returns false when interrupted by
     * [stop] or when speaking failed.
     */
    suspend fun speak(text: String): Boolean

    /** Applies new pitch and rate to what is rendered from now on, without reloading the voice. */
    fun adjust(pitch: Float, rate: Float)

    /** Interrupts the sentence currently being spoken. */
    fun stop()

    fun release()
}

/** A piece of a story to speak; a short breath follows when it [endsSentence]. */
data class SpeechPiece(val text: String, val endsSentence: Boolean)

/** The narrator to use, plus why it is not the one the user picked, if that is the case. */
data class NarratorChoice(val narrator: Narrator, val warning: String?)

object Narrators {
    private const val TAG = "Narrators"

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** The pieces to speak [sentences] in: long sentences are cut into clauses for on-device voices. */
    fun pieces(narrator: Narrator, sentences: List<String>): List<SpeechPiece> = sentences.flatMap { sentence ->
        val parts = if (narrator.rendersOnDevice) SpeechChunker.chunks(sentence) else listOf(sentence)
        parts.mapIndexed { i, part -> SpeechPiece(part, endsSentence = i == parts.lastIndex) }
    }

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
