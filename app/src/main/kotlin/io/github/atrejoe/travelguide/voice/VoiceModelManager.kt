package io.github.atrejoe.travelguide.voice

import android.content.Context
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Files of an installed voice model, as sherpa-onnx needs them. */
data class InstalledModel(val model: File, val tokens: File, val espeakData: File)

/** Downloads, unpacks and removes on-device voice models. */
class VoiceModelManager(context: Context) {
    private val root = File(context.filesDir, "voices")
    private val cache = context.cacheDir

    private val mutableProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Download progress per voice id (0..1, or -1 while unpacking / size unknown). */
    val progress: StateFlow<Map<String, Float>> = mutableProgress.asStateFlow()

    private val mutableInstalled = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = mutableInstalled.asStateFlow()

    fun isInstalled(voice: NeuralVoice): Boolean = File(dirFor(voice), MARKER).exists()

    fun model(voice: NeuralVoice): InstalledModel? {
        if (!isInstalled(voice)) return null
        val files = dirFor(voice).walkTopDown()
        val onnx = files.firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null
        val espeak = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
        return InstalledModel(onnx, tokens, espeak)
    }

    suspend fun download(voice: NeuralVoice) = withContext(Dispatchers.IO) {
        val archive = File(cache, "${voice.id}.tar.bz2")
        val target = dirFor(voice)
        try {
            setProgress(voice, 0f)
            fetch(voice.archiveUrl, archive) { setProgress(voice, it) }
            setProgress(voice, -1f)
            target.deleteRecursively()
            unpack(archive, target)
            File(target, MARKER).writeText(voice.archiveUrl)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        } finally {
            archive.delete()
            mutableProgress.update { it - voice.id }
            mutableInstalled.value = scanInstalled()
        }
    }

    fun delete(voice: NeuralVoice) {
        dirFor(voice).deleteRecursively()
        mutableInstalled.value = scanInstalled()
    }

    private fun dirFor(voice: NeuralVoice) = File(root, voice.id)

    private fun scanInstalled(): Set<String> =
        VoiceCatalog.neuralVoices.filter { File(dirFor(it), MARKER).exists() }.map { it.id }.toSet()

    private fun setProgress(voice: NeuralVoice, value: Float) =
        mutableProgress.update { it + (voice.id to value) }

    private suspend fun fetch(url: String, dest: File, onProgress: (Float) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", UrlConnectionFetcher.USER_AGENT)
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            val total = connection.contentLengthLong
            var done = 0L
            var lastReported = 0f
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val fraction = done.toFloat() / total
                            if (fraction - lastReported >= 0.01f) {
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

    /** Extracts the archive, dropping its top-level folder and refusing paths that escape [target]. */
    private suspend fun unpack(archive: File, target: File) {
        val canonicalTarget = target.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val out = File(canonicalTarget, relative).canonicalFile
                if (!out.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw IOException("Refusing to unpack ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isFile) {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private companion object {
        const val MARKER = ".installed"
    }
}
