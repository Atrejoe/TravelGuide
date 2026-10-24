package io.github.atrejoe.travelguide.voice

import android.util.Log
import io.github.atrejoe.travelguide.core.voice.ElevenLabsClient
import io.github.atrejoe.travelguide.core.voice.ElevenLabsException
import io.github.atrejoe.travelguide.core.voice.ElevenLabsModel
import io.github.atrejoe.travelguide.core.voice.SpeechCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * An ElevenLabs voice, with the listener's own API key (issue #75). One voice speaks every story
 * language, and sounds more natural than any on-device one, but it costs credits and needs a
 * connection.
 *
 * To spend few credits, speech is fetched only [LOOKAHEAD] pieces ahead, so a story cut short
 * costs little, and kept in a [SpeechCache], so a replay costs nothing. Without a connection the
 * story goes on in an on-device voice from [fallback], made when first needed; ElevenLabs is
 * tried again after [RETRY_MILLIS]. When the credits run out or the key is refused, the fallback
 * reads on for the rest of the trip, and [notice] says why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElevenLabsNarrator(
    private val client: ElevenLabsClient,
    private val voiceId: String,
    voiceName: String,
    private val model: ElevenLabsModel,
    private val lang: String,
    private val cache: SpeechCache,
    pitch: Float,
    rate: Float,
    private val fallback: suspend () -> NarratorChoice,
) : Narrator {
    override val label: String = "${voiceName.ifBlank { "ElevenLabs voice" }} (ElevenLabs)"

    @Volatile private var currentNotice: String? = null
    override val notice: String? get() = currentNotice

    @Volatile private var rate: Float = rate

    private val stream = AudioStream("elevenlabs-audio").also { it.pitch = pitch }

    /** Two requests at a time at most: ElevenLabs limits concurrent requests per plan. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    /** The pieces of the story being told, from [prepare]. Only touched from the caller's thread. */
    private var pieces: List<String> = emptyList()

    /** Speech fetched or being fetched. Only touched from the caller's thread. */
    private val fetched = HashMap<String, Deferred<FloatArray>>()

    /** The piece spoken last, kept to repeat it after a pause. Only touched from the caller's thread. */
    private var last: Pair<String, FloatArray>? = null

    /** The on-device voice reading while ElevenLabs can't, made when first needed. */
    @Volatile private var local: Narrator? = null

    /** Whether the last piece went to [local], whose audio then has to play out first. */
    private var localSpeaking = false

    @Volatile private var offlineUntil = 0L
    @Volatile private var refused = false
    @Volatile private var stopped = false
    @Volatile private var released = false

    private val online: Boolean get() = !refused && System.currentTimeMillis() >= offlineUntil

    override fun prepare(texts: List<String>) {
        pieces = texts
        val wanted = texts.toSet()
        val iterator = fetched.entries.iterator()
        while (iterator.hasNext()) {
            val (text, job) = iterator.next()
            if (text !in wanted) {
                job.cancel() // A request already sent is still billed; there are at most a few.
                iterator.remove()
            }
        }
        if (online) fetchAhead(0) else local?.prepare(texts)
    }

    override fun isPrepared(text: String): Boolean =
        if (online) last?.first == text || fetched[text]?.isCompleted == true else local?.isPrepared(text) == true

    private fun fetchAhead(from: Int) {
        for (index in from until minOf(from + LOOKAHEAD, pieces.size)) {
            val text = pieces[index]
            if (text !in fetched && last?.first != text) fetched[text] = fetch(text, pieces.getOrNull(index - 1), pieces.getOrNull(index + 1))
        }
    }

    private fun fetch(text: String, previous: String?, next: String?): Deferred<FloatArray> {
        val speed = rate
        return scope.async {
            val key = SpeechCache.key(voiceId, model.id, speed, text)
            val bytes = cache.get(key) ?: request(text, speed, previous, next).also { cache.put(key, it) }
            ElevenLabsClient.pcm16ToFloat(bytes)
        }
    }

    /** Tries once more when ElevenLabs is busy. */
    private suspend fun request(text: String, speed: Float, previous: String?, next: String?): ByteArray = try {
        client.speech(voiceId, text, model, lang, speed, previous, next)
    } catch (e: ElevenLabsException) {
        if (e.reason != ElevenLabsException.Reason.BUSY) throw e
        delay(BUSY_RETRY_MILLIS)
        client.speech(voiceId, text, model, lang, speed, previous, next)
    }

    override suspend fun speak(text: String): Boolean {
        if (released) return false
        stopped = false
        stream.resume()
        if (online) {
            val index = pieces.indexOf(text)
            if (index >= 0) fetchAhead(index)
            val samples = try {
                last?.takeIf { it.first == text }?.second
                    ?: (fetched.remove(text) ?: fetch(text, null, null)).await().also { last = text to it }
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                return false // The piece was dropped from the story meanwhile.
            } catch (t: Throwable) {
                failed(t)
                null
            }
            if (samples != null) {
                if (stopped || released || samples.isEmpty()) return false
                currentNotice = null
                if (localSpeaking) {
                    local?.drain()
                    localSpeaking = false
                }
                return stream.write(samples, ElevenLabsClient.SAMPLE_RATE)
            }
        }
        if (stopped || released) return false
        val narrator = local ?: fallback().narrator.also { local = it }
        if (released) return false
        if (!localSpeaking) {
            stream.drain()
            localSpeaking = true
            val index = pieces.indexOf(text)
            if (index >= 0) narrator.prepare(pieces.drop(index))
        }
        return narrator.speak(text)
    }

    /** Switches to the on-device voice, for now or for good, and says so. */
    private fun failed(t: Throwable) {
        Log.w(TAG, "ElevenLabs failed", t)
        fetched.values.forEach { it.cancel() }
        fetched.clear()
        val e = t as? ElevenLabsException
        if (e != null && e.permanent) {
            refused = true
            currentNotice = when (e.reason) {
                ElevenLabsException.Reason.QUOTA_EXCEEDED ->
                    "Your ElevenLabs credits are used up, so an on-device voice reads on."
                else -> "ElevenLabs refused your API key (${e.message}), so an on-device voice reads on. Check the key in Settings."
            }
        } else {
            offlineUntil = System.currentTimeMillis() + RETRY_MILLIS
            currentNotice = if (e != null) {
                "ElevenLabs had a problem (${e.message}), so an on-device voice reads for now."
            } else {
                "No connection to ElevenLabs, so an on-device voice reads until it's back."
            }
        }
    }

    override suspend fun silence(millis: Long) {
        if (stopped || released) return
        val narrator = local
        if (localSpeaking && narrator != null) narrator.silence(millis) else stream.silence(millis)
    }

    override suspend fun drain() {
        if (released) return
        stream.drain()
        local?.drain()
        localSpeaking = false
    }

    /** Speech already fetched keeps the old speed; there are only a few pieces of it. */
    override fun adjust(pitch: Float, rate: Float) {
        this.rate = rate
        stream.pitch = pitch
        local?.adjust(pitch, rate)
    }

    override fun stop() {
        stopped = true
        stream.stop()
        local?.stop()
    }

    override fun release() {
        if (released) return
        released = true
        stop()
        fetched.clear()
        last = null
        scope.cancel()
        stream.release()
        local?.release()
        local = null
    }

    private companion object {
        const val TAG = "ElevenLabsNarrator"

        /** Pieces fetched ahead of the one speaking: enough to never wait, few enough to waste little. */
        const val LOOKAHEAD = 3

        /** How long to read with the on-device voice before trying ElevenLabs again. */
        const val RETRY_MILLIS = 60_000L

        const val BUSY_RETRY_MILLIS = 1_500L
    }
}
