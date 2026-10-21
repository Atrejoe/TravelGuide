package io.github.atrejoe.travelguide.voice

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.atrejoe.travelguide.core.voice.ModelFiles
import io.github.atrejoe.travelguide.core.voice.SynthesisThreads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * On-device neural voice: Kokoro or Piper models run by sherpa-onnx. Free and offline.
 *
 * All synthesis runs on one dedicated thread, so the native engine is never used concurrently
 * and is released only after any synthesis in flight. A whole story is rendered ahead with
 * [prepare], in order, so the next piece is usually ready before the current one has played.
 *
 * The pieces of a story play back to back on one audio stream (issue #63): [speak] returns once
 * its piece is handed to the stream, so the next one follows without a gap, and [drain] lets the
 * last one play out. The stream is only touched on its own thread, except [stop], which pauses
 * and flushes it to cut the story off at once.
 */
class NeuralNarrator(
    private val voice: NeuralVoice,
    private val files: ModelFiles,
    pitch: Float,
    rate: Float,
) : Narrator {
    override val label: String = "${voice.name} (on-device)"
    override val rendersOnDevice: Boolean = true

    @Volatile private var pitch: Float = pitch
    @Volatile private var rate: Float = rate

    @Volatile private var measuredFactor: Double? = null
    override val realTimeFactor: Double? get() = measuredFactor

    private val synthExecutor = Executors.newSingleThreadExecutor { Thread(it, "tts-synth") }
    private val scope = CoroutineScope(SupervisorJob() + synthExecutor.asCoroutineDispatcher())

    /** Only touched on the synthesis thread. */
    private var tts: OfflineTts? = null

    /** Audio rendered or being rendered, in speaking order. Only touched from the caller's thread. */
    private val queued = LinkedHashMap<String, Deferred<GeneratedAudio>>()

    /** The piece spoken last, kept to repeat it after a pause. Only touched from the caller's thread. */
    private var last: Pair<String, GeneratedAudio>? = null

    @Volatile private var stopped = false
    @Volatile private var released = false

    private val audioExecutor = Executors.newSingleThreadExecutor { Thread(it, "tts-audio") }
    private val audioThread = audioExecutor.asCoroutineDispatcher()

    /** The open stream, its sample rate and the frames written to it. Only touched on [audioThread]. */
    @Volatile private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackPitch = 1f
    private var framesWritten = 0L

    /** Set by [stop], which flushed the stream: the next piece starts on a fresh one. */
    @Volatile private var cut = false

    /**
     * Loads the model, so failures surface before the first story and not halfway through it, and
     * renders a throwaway word: the first run of a model is much slower than the ones after it.
     */
    suspend fun warmUp() {
        scope.async { engine().generate(text = "Hello.", sid = voice.speakerId, speed = rate) }.await()
    }

    private fun engine(): OfflineTts = tts ?: OfflineTts(assetManager = null, config = config()).also { tts = it }

    private fun config(): OfflineTtsConfig {
        val threads = synthesisThreads
        val model = when (voice.pkg.engine) {
            VoiceEngine.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = files.model.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            VoiceEngine.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = files.model.absolutePath,
                    voices = requireNotNull(files.voices) { "voices.bin missing" }.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model)
    }

    private fun synthesize(text: String): Deferred<GeneratedAudio> = scope.async {
        val started = System.nanoTime()
        engine().generate(text = text, sid = voice.speakerId, speed = rate).also { audio ->
            if (audio.samples.isNotEmpty()) {
                val factor = (System.nanoTime() - started) / 1e9 / (audio.samples.size.toDouble() / audio.sampleRate)
                measuredFactor = measuredFactor?.let { it * 0.7 + factor * 0.3 } ?: factor
            }
        }
    }

    /** Pieces already rendered keep the old rate; there are only a few of them. */
    override fun adjust(pitch: Float, rate: Float) {
        this.pitch = pitch
        this.rate = rate
    }

    override fun prepare(texts: List<String>) {
        val wanted = texts.toSet()
        val iterator = queued.entries.iterator()
        while (iterator.hasNext()) {
            val (text, job) = iterator.next()
            if (text !in wanted) {
                job.cancel() // Skipped if it hasn't started; a running one finishes unused.
                iterator.remove()
            }
        }
        for (text in texts) if (text !in queued) queued[text] = synthesize(text)
    }

    override suspend fun speak(text: String): Boolean {
        if (released) return false
        stopped = false
        val audio = try {
            last?.takeIf { it.first == text }?.second
                ?: (queued.remove(text) ?: synthesize(text)).await().also { last = text to it }
        } catch (t: Throwable) {
            coroutineContext.ensureActive()
            Log.e(TAG, "Speech synthesis failed", t)
            return false
        }
        if (stopped || released || audio.samples.isEmpty()) return false
        return withContext(audioThread) { write(audio.samples, audio.sampleRate) }
    }

    /** A breath between sentences, written to the stream as silence so it lasts exactly [millis]. */
    override suspend fun silence(millis: Long) {
        if (stopped || released) return
        withContext(audioThread) {
            val rate = trackRate.takeIf { track != null } ?: return@withContext delay(millis)
            write(FloatArray((rate * millis / 1000).toInt()), rate)
        }
    }

    /** Lets what is still in the stream play out, then closes it. */
    override suspend fun drain() {
        if (released) return
        withContext(audioThread) {
            val audioTrack = track ?: return@withContext
            if (!stopped) {
                // In streaming mode stop() plays what was written, then stops.
                audioTrack.stop()
                val deadline = System.currentTimeMillis() + 1_000 + framesWritten * 1000 / trackRate
                while (!stopped && (audioTrack.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(20)
                }
            }
            closeTrack()
        }
    }

    /** Writes [samples] to the stream, opening it first if needed. Runs on [audioThread]. */
    private fun write(samples: FloatArray, sampleRate: Int): Boolean {
        val audioTrack = openTrack(sampleRate) ?: return false
        val chunk = sampleRate / 10
        var offset = 0
        while (offset < samples.size && !stopped) {
            val n = minOf(chunk, samples.size - offset)
            val written = audioTrack.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                closeTrack()
                return false
            }
            offset += written
            framesWritten += written
        }
        return !stopped
    }

    private fun openTrack(sampleRate: Int): AudioTrack? {
        if (cut) {
            cut = false
            closeTrack()
        }
        track?.let { if (trackRate == sampleRate) return it.also { applyPitch(it) } }
        closeTrack()
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(Narrators.audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize(sampleRate))
                .build()
        } catch (e: RuntimeException) {
            // The story fails, not the app (issue #53).
            Log.e(TAG, "Cannot play audio at $sampleRate Hz", e)
            return null
        }
        track = audioTrack
        trackRate = sampleRate
        trackPitch = 1f
        framesWritten = 0
        applyPitch(audioTrack)
        audioTrack.play()
        return audioTrack
    }

    /**
     * Pitch shifting is done by time-stretching, which adds a metallic edge; only used when the
     * user asked for a different pitch.
     */
    private fun applyPitch(audioTrack: AudioTrack) {
        val wanted = pitch
        if (abs(wanted - trackPitch) < 0.01f) return
        try {
            audioTrack.playbackParams = PlaybackParams().setPitch(wanted).setSpeed(1f)
            trackPitch = wanted
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Device does not support pitch $wanted", e)
            trackPitch = wanted
        }
    }

    private fun closeTrack() {
        val audioTrack = track ?: return
        track = null
        try {
            audioTrack.pause()
            audioTrack.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Closing a stopped track", e)
        }
        audioTrack.release()
    }

    /**
     * About 0.25 s of mono float samples, at least what the device needs, in whole frames of 4
     * bytes: AudioTrack refuses anything else, e.g. 22 050 bytes for a 22 050 Hz Piper voice (#53).
     */
    private fun bufferSize(sampleRate: Int): Int {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = maxOf(minBuffer, sampleRate)
        return (bytes + FRAME_BYTES - 1) / FRAME_BYTES * FRAME_BYTES
    }

    override fun stop() {
        stopped = true
        cut = true
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stopping released track", e)
            }
        }
    }

    override fun release() {
        if (released) return
        released = true
        stop()
        queued.clear()
        last = null
        scope.cancel()
        audioExecutor.execute { closeTrack() }
        audioExecutor.shutdown()
        // Runs after any synthesis in flight: the executor is single-threaded.
        synthExecutor.execute {
            tts?.release()
            tts = null
        }
        synthExecutor.shutdown()
    }

    private companion object {
        const val TAG = "NeuralNarrator"

        /** One mono sample of 32-bit float PCM. */
        const val FRAME_BYTES = 4

        /** Threads for the voice model: the phone's fast cores, see [SynthesisThreads]. */
        val synthesisThreads: Int by lazy {
            val cores = Runtime.getRuntime().availableProcessors()
            val frequencies = (0 until cores).map { core ->
                try {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                } catch (e: Exception) {
                    0L
                }
            }
            SynthesisThreads.forCores(frequencies, fallback = cores).also {
                Log.i(TAG, "$it synthesis threads; cores at ${frequencies.joinToString()} kHz")
            }
        }
    }
}
