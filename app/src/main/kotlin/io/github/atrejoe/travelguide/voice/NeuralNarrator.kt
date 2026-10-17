package io.github.atrejoe.travelguide.voice

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device neural voice (Piper models run by sherpa-onnx). Free, offline and much more natural
 * than most built-in voices. Pitch is lowered on playback for a deeper, calmer narrator.
 */
class NeuralNarrator(
    private val model: InstalledModel,
    private val pitch: Float,
    private val rate: Float,
) : Narrator {
    private val lock = Mutex()
    @Volatile private var tts: OfflineTts? = null

    @Volatile private var stopped = false
    @Volatile private var released = false
    @Volatile private var track: AudioTrack? = null

    private fun engine(): OfflineTts = tts ?: OfflineTts(
        assetManager = null,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.model.absolutePath,
                    tokens = model.tokens.absolutePath,
                    dataDir = model.espeakData.absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        ),
    ).also { tts = it }

    override suspend fun speak(text: String): Boolean = lock.withLock {
        if (released) return false
        stopped = false
        val audio = try {
            withContext(Dispatchers.Default) { engine().generate(text = text, sid = 0, speed = rate) }
        } catch (e: Exception) {
            Log.e(TAG, "Speech synthesis failed", e)
            return false
        }
        if (released) freeEngine()
        if (stopped || released || audio.samples.isEmpty()) return false
        withContext(Dispatchers.IO) { play(audio.samples, audio.sampleRate) }
    }

    private suspend fun play(samples: FloatArray, sampleRate: Int): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(Narrators.audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate)) // ~0.25 s of float samples
            .build()
        track = audioTrack
        try {
            try {
                audioTrack.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Device does not support pitch $pitch", e)
            }
            audioTrack.play()
            val chunk = sampleRate / 10
            var offset = 0
            while (offset < samples.size && !stopped) {
                val n = minOf(chunk, samples.size - offset)
                val written = audioTrack.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) return false
                offset += written
            }
            if (stopped) return false
            // Let the buffered tail play out before reporting completion.
            audioTrack.stop()
            val deadline = System.currentTimeMillis() + 2_000
            while (!stopped && audioTrack.playbackHeadPosition < samples.size &&
                System.currentTimeMillis() < deadline
            ) {
                delay(30)
            }
            return !stopped
        } finally {
            track = null
            audioTrack.release()
        }
    }

    override fun stop() {
        stopped = true
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
        released = true
        stop()
        // Never free the native engine while it is synthesising; speak() frees it afterwards.
        if (lock.tryLock()) {
            try {
                freeEngine()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun freeEngine() {
        tts?.release()
        tts = null
    }

    private companion object {
        const val TAG = "NeuralNarrator"
    }
}
