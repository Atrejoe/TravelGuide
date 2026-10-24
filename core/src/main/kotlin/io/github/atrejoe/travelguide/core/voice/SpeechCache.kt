package io.github.atrejoe.travelguide.core.voice

import java.io.File
import java.security.MessageDigest

/**
 * Speech fetched from a paid voice, kept on the phone (issue #75): a replayed story or a second
 * preview costs nothing. The oldest files go once the cache grows past [maxBytes].
 */
class SpeechCache(private val dir: File, private val maxBytes: Long = 64L * 1024 * 1024) {

    fun get(key: String): ByteArray? {
        val file = file(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            null
        }
    }

    fun put(key: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "${name(key)}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file(key))
        trim()
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxBytes) f.delete()
        }
    }

    private fun file(key: String) = File(dir, name(key) + EXTENSION)

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val EXTENSION = ".pcm"

        /** Everything that changes how [text] sounds. */
        fun key(voiceId: String, model: String, speed: Float, text: String) = "$voiceId|$model|${"%.2f".format(java.util.Locale.ROOT, speed)}|$text"
    }
}
