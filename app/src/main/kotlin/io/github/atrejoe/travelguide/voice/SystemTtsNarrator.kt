package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Uses whatever text-to-speech engine is installed on the device. */
class SystemTtsNarrator(
    context: Context,
    private val voiceName: String?,
    private val pitch: Float,
    private val rate: Float,
    private val locale: Locale,
) : Narrator {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    override var label: String = "System voice"
        private set

    private var configured = false

    /**
     * Waits for the engine and applies the voice settings. Returns a warning when the requested
     * voice cannot be used (for example because its data is not installed), in which case the
     * engine's default voice for [locale] speaks instead.
     */
    suspend fun initialize(): String? {
        if (!ready.await()) return "The phone's text-to-speech engine is not available."
        if (configured) return null
        configured = true
        tts.setAudioAttributes(Narrators.audioAttributes)
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val wanted: Voice? = voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (wanted != null && SystemVoices.isInstalled(wanted) && tts.setVoice(wanted) == TextToSpeech.SUCCESS) {
            label = "System voice ${wanted.name}"
            return null
        }
        tts.language = locale
        label = "System voice (default)"
        return voiceName?.let { "System voice $it is not installed on this phone; using the default voice." }
    }

    override suspend fun speak(text: String): Boolean {
        if (!ready.await()) return false
        initialize()
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                tts.stop()
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                finish(id, false)
            }
        }
    }

    private fun finish(utteranceId: String?, completed: Boolean) {
        val cont = utteranceId?.let { pending.remove(it) } ?: return
        if (cont.isActive) cont.resume(completed)
    }

    override fun stop() {
        tts.stop()
        pending.keys.toList().forEach { finish(it, false) }
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

/** A selectable Android TTS voice. */
data class SystemVoice(val name: String, val label: String)

object SystemVoices {
    /** Engines list voices whose data still has to be downloaded; those silently fall back. */
    fun isInstalled(voice: Voice): Boolean =
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

    /** Lists installed English voices, best quality first. */
    suspend fun load(context: Context): List<SystemVoice> {
        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }
        return try {
            if (!ready.await()) return emptyList()
            tts.voices.orEmpty()
                .filter { it.locale.language == "en" && isInstalled(it) }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
                .map { v ->
                    val where = if (v.isNetworkConnectionRequired) "online" else "offline"
                    SystemVoice(v.name, "${v.locale.displayName} · ${v.name} · $where")
                }
        } finally {
            tts.shutdown()
        }
    }
}
