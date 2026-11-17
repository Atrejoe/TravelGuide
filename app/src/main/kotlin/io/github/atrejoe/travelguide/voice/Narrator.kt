package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import io.github.atrejoe.travelguide.R
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

    /**
     * Renders short lines to keep at hand for the whole trip, such as what the guide says while
     * the listener waits (issue #82); unlike [prepare], later calls don't drop them.
     */
    fun prepareAside(texts: List<String>) = Unit

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
                return choice.copy(warning = choice.warning ?: context.getString(R.string.warn_elevenlabs_key))
            }
            val narrator = ElevenLabsNarrator(
                context = context,
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
            warning = context.getString(R.string.warn_wrong_language, neural.name, languageName(context, language.code))
        } else if (neural != null) {
            val files = models.files(neural.pkg)
            if (files == null) {
                warning = context.getString(R.string.warn_not_downloaded, neural.name)
            } else {
                val narrator = NeuralNarrator(neural, files, settings.pitch, settings.rate, context.getString(R.string.label_on_device, neural.name))
                try {
                    narrator.warmUp()
                    return NarratorChoice(narrator, null)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not load ${neural.id}", t)
                    narrator.release()
                    warning = context.getString(R.string.warn_not_loaded, neural.name, t.message ?: t.javaClass.simpleName)
                }
            }
        } else if (settings.voiceId.startsWith(VoiceCatalog.NEURAL_PREFIX)) {
            warning = context.getString(R.string.warn_voice_gone)
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

    /** The name of a language in the language of [context]'s screens, as written within a sentence. */
    fun languageName(context: Context, code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(context.resources.configuration.locales[0])

    /** The system voice's locale for a story language: British English, as the neural voices. */
    fun locale(lang: String): Locale = if (lang == "en") Locale.UK else Locale.forLanguageTag(lang)
}
