package io.github.atrejoe.travelguide.voice

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * One audio stream the pieces of a story play on back to back (issue #63), for voices that hand
 * over their own samples: [write] returns once a piece is queued, so the next one follows without
 * a gap, and [drain] lets the last one play out. The stream is only touched on its own thread,
 * except [stop], which pauses and flushes it to cut the story off at once.
 */
class AudioStream(threadName: String) {
    /** Applied to what is written from now on. */
    @Volatile var pitch: Float = 1f

    @Volatile private var stopped = false
    @Volatile private var released = false

    private val executor = Executors.newSingleThreadExecutor { Thread(it, threadName) }
    private val thread = executor.asCoroutineDispatcher()

    /** The open stream, its sample rate and the frames written to it. Only touched on [thread]. */
    @Volatile private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackPitch = 1f
    private var framesWritten = 0L

    /** Set by [stop], which flushed the stream: the next piece starts on a fresh one. */
    @Volatile private var cut = false

    /** Clears an earlier [stop]; call it before speaking again. */
    fun resume() {
        stopped = false
    }

    /** Queues [samples] for playback, opening the stream first if needed; false if stopped or failed. */
    suspend fun write(samples: FloatArray, sampleRate: Int): Boolean {
        if (stopped || released) return false
        return withContext(thread) { writeNow(samples, sampleRate) }
    }

    /** A breath between sentences, written to the stream as silence so it lasts exactly [millis]. */
    suspend fun silence(millis: Long) {
        if (stopped || released) return
        withContext(thread) {
            val rate = trackRate.takeIf { track != null } ?: return@withContext delay(millis)
            writeNow(FloatArray((rate * millis / 1000).toInt()), rate)
        }
    }

    /** Lets what is still in the stream play out, then closes it. */
    suspend fun drain() {
        if (released) return
        withContext(thread) {
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

    /** Runs on [thread]. */
    private fun writeNow(samples: FloatArray, sampleRate: Int): Boolean {
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

    /** Cuts off what is playing at once. */
    fun stop() {
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

    fun release() {
        if (released) return
        released = true
        stop()
        executor.execute { closeTrack() }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "AudioStream"

        /** One mono sample of 32-bit float PCM. */
        const val FRAME_BYTES = 4
    }
}
