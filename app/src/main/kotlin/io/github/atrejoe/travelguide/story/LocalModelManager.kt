package io.github.atrejoe.travelguide.story

import android.content.Context
import io.github.atrejoe.travelguide.core.enhance.LocalModel
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads the open models for the on-device storyteller (issue #51). They are large, so a
 * download that broke off continues where it stopped.
 */
class LocalModelManager(context: Context) {
    private val root = File(context.filesDir, "models")

    private val mutableProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Download progress per model id, 0..1 (-1 while checking the file). */
    val progress: StateFlow<Map<String, Float>> = mutableProgress.asStateFlow()

    private val mutableInstalled = MutableStateFlow(scanInstalled())

    /** Ids of downloaded models. */
    val installed: StateFlow<Set<String>> = mutableInstalled.asStateFlow()

    fun file(model: LocalModel): File? = fileFor(model).takeIf { it.exists() }

    suspend fun download(model: LocalModel) = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        val part = File(root, "${model.id}.part")
        root.mkdirs()
        val needed = model.sizeMb * 1_000_000L - part.length() + FREE_SPACE_MARGIN
        if (root.usableSpace < needed) {
            throw IOException("Not enough free space: ${model.name} needs about ${model.sizeMb} MB")
        }
        try {
            mutableProgress.update { it + (model.id to 0f) }
            fetch(model.url, part) { fraction -> mutableProgress.update { it + (model.id to fraction) } }
            mutableProgress.update { it + (model.id to -1f) }
            verify(model, part)
            if (!part.renameTo(target)) throw IOException("Could not store the model")
        } catch (e: IOException) {
            // A damaged file starts over; a broken connection continues next time.
            if (e is DamagedModelException) part.delete()
            throw e
        } finally {
            mutableProgress.update { it - model.id }
            mutableInstalled.value = scanInstalled()
        }
    }

    fun delete(model: LocalModel) {
        fileFor(model).delete()
        File(root, "${model.id}.part").delete()
        mutableInstalled.value = scanInstalled()
    }

    private fun fileFor(model: LocalModel) = File(root, "${model.id}.gguf")

    private fun scanInstalled(): Set<String> = LocalModels.all.filter { fileFor(it).exists() }.map { it.id }.toSet()

    private class DamagedModelException(message: String) : IOException(message)

    private fun verify(model: LocalModel, file: File) {
        val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (!magic.contentEquals("GGUF".toByteArray())) throw DamagedModelException("The download is not a model file")
        val expected = model.sha256 ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) throw DamagedModelException("The download is damaged; please try again")
    }

    /** Downloads [url] to [dest], continuing a partial file. */
    private suspend fun fetch(url: String, dest: File, onProgress: (Float) -> Unit) {
        val start = dest.length()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", UrlConnectionFetcher.USER_AGENT)
            if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
            val code = connection.responseCode
            if (code == 416) return // Already complete.
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val total = connection.contentLengthLong.let { if (it > 0) it + (if (resumed) start else 0) else -1 }
            var done = if (resumed) start else 0L
            var lastReported = -1f
            connection.inputStream.use { input ->
                FileOutputStream(dest, resumed).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val fraction = done.toFloat() / total
                            if (fraction - lastReported >= 0.005f) {
                                lastReported = fraction
                                onProgress(fraction)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val FREE_SPACE_MARGIN = 200_000_000L
    }
}
