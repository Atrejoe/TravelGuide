package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.story.SpeechChunker
import io.github.atrejoe.travelguide.core.voice.ElevenLabsClient
import io.github.atrejoe.travelguide.core.voice.ElevenLabsModel
import io.github.atrejoe.travelguide.net.UrlConnectionTransport
import io.github.atrejoe.travelguide.settings.AppSettings
import kotlinx.coroutines.delay
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

    /** What the listener should know about the voice right now, e.g. that another one reads for it. */
    val notice: String? get() = null

    /** Starts rendering [texts] in order, before they are spoken; drops anything else still queued. */
    fun prepare(texts: List<String>) = Unit

    /** False while [text] is still being rendered, so speaking it would have to wait. */
    fun isPrepared(text: String): Boolean = true

    /**
     * Speaks [text] and suspends until it has been played, or, for a voice that streams its
     * pieces back to back, until it is queued for playback (see [drain]). Returns false when
     * interrupted by [stop] or when speaking failed.
     */
    suspend fun speak(text: String): Boolean

    /** A pause of [millis] between sentences, timed with what is still to be heard. */
    suspend fun silence(millis: Long) = delay(millis)

    /** Suspends until everything spoken has been heard; call it when a story ends. */
    suspend fun drain() = Unit

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
     * fallback once made a male voice sound like a robotic female one. An ElevenLabs voice has an
     * on-device voice for [settings]' language to fall back to (issue #75).
     */
    suspend fun create(context: Context, settings: AppSettings, models: VoiceModelManager): NarratorChoice {
        val cloudVoice = VoiceCatalog.elevenLabsVoice(settings.voiceId)
        if (cloudVoice != null) {
            // ElevenLabs speaks every story language; an on-device voice stands in without a connection.
            val local = settings.copy(voiceId = localVoiceId(settings.language, models))
            val key = context.app.elevenLabsKeys.get()
            if (key == null) {
                val choice = create(context, local, models)
                return choice.copy(warning = choice.warning ?: "ElevenLabs needs your API key (Settings), so an on-device voice is used.")
            }
            val narrator = ElevenLabsNarrator(
                client = ElevenLabsClient(key, UrlConnectionTransport()),
                voiceId = cloudVoice,
                voiceName = settings.elevenLabsVoiceName,
                model = ElevenLabsModel.byId(settings.elevenLabsModel),
                lang = settings.language,
                cache = context.app.speechCache,
                pitch = settings.pitch,
                rate = settings.rate,
                fallback = { create(context, local, models) },
            )
            return NarratorChoice(narrator, null)
        }
        var warning: String? = null
        val language = StoryLanguages.of(settings.language)
        val neural = VoiceCatalog.neural(settings.voiceId)
        if (neural != null && neural.lang != language.code) {
            // An English voice reading Dutch sounds like nonsense (issue #68).
            warning = "${neural.name} doesn't speak ${language.name}, so the system voice is used. Pick a ${language.name} voice in Settings."
        } else if (neural != null) {
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
            locale = locale(language.code),
        )
        val systemWarning = system.initialize()
        return NarratorChoice(system, warning ?: systemWarning)
    }

    /** The first downloaded on-device voice for [lang], else the system voice. */
    private fun localVoiceId(lang: String, models: VoiceModelManager): String =
        VoiceCatalog.voicesFor(lang).firstOrNull { models.files(it.pkg) != null }?.voiceId ?: VoiceCatalog.SYSTEM_PREFIX

    /** The system voice's locale for a story language: British English, as the neural voices. */
    fun locale(lang: String): Locale = if (lang == "en") Locale.UK else Locale.forLanguageTag(lang)
}
