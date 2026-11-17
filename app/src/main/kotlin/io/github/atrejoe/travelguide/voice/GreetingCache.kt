package io.github.atrejoe.travelguide.voice

import android.util.Log
import io.github.atrejoe.travelguide.core.lang.DayPart
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Speech rendered ahead, to play without the voice that rendered it. */
class RenderedSpeech(val samples: FloatArray, val sampleRate: Int)

/**
 * The greeting for the next start, one per part of the day, rendered by the chosen voice during an
 * earlier trip: an on-device voice takes seconds to load, and this lets the guide say hello at
 * once, in that voice, while it loads. Each greeting is kept with the voice, rate and language it
 * was rendered for, and only played for those.
 */
class GreetingCache(private val dir: File) {

    /** The greeting kept for [dayPart] and the voice [key], if any. */
    fun load(key: String, dayPart: DayPart): Pair<String, RenderedSpeech>? {
        val file = file(dayPart)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readUTF() != key) return null
                val text = input.readUTF()
                val sampleRate = input.readInt()
                val bytes = ByteArray(input.readInt() * Float.SIZE_BYTES)
                input.readFully(bytes)
                val samples = FloatArray(bytes.size / Float.SIZE_BYTES)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                text to RenderedSpeech(samples, sampleRate)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Can't read the greeting for $dayPart", e)
            null
        }
    }

    /** Keeps [text], rendered as [speech] by the voice [key], for the next start in [dayPart]. */
    fun store(key: String, dayPart: DayPart, text: String, speech: RenderedSpeech) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${dayPart.name}.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(key)
                out.writeUTF(text)
                out.writeInt(speech.sampleRate)
                out.writeInt(speech.samples.size)
                val bytes = ByteBuffer.allocate(speech.samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(speech.samples)
                out.write(bytes.array())
            }
            if (!tmp.renameTo(file(dayPart))) tmp.delete()
        } catch (e: IOException) {
            Log.w(TAG, "Can't keep the greeting for $dayPart", e)
        }
    }

    private fun file(dayPart: DayPart) = File(dir, "${dayPart.name}.greeting")

    private companion object {
        const val TAG = "GreetingCache"
        const val MAGIC = 0x54474731 // "TGG1"
    }
}
