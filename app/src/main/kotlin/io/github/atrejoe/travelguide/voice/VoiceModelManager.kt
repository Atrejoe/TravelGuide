package io.github.atrejoe.travelguide.voice

import android.content.Context
import android.util.Log
import io.github.atrejoe.travelguide.core.voice.ModelArchive
import io.github.atrejoe.travelguide.core.voice.ModelFiles
import io.github.atrejoe.travelguide.core.voice.ModelLayout
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/** Downloads, unpacks and removes on-device voice model packages. */
class VoiceModelManager(context: Context) {
    private val root = File(context.filesDir, "voices")
    private val cache = context.cacheDir

    private val mutableProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Download progress per package id (0..1, or -1 while unpacking / size unknown). */
    val progress: StateFlow<Map<String, Float>> = mutableProgress.asStateFlow()

    private val mutableInstalled = MutableStateFlow(scanInstalled())

    /** Ids of installed packages. */
    val installed: StateFlow<Set<String>> = mutableInstalled.asStateFlow()

    init {
        // Models from earlier versions of the catalog are no longer used; free their space.
        thread(name = "voice-cleanup", isDaemon = true) {
            val known = VoiceCatalog.packages.map { it.id }.toSet()
            root.listFiles()?.filter { it.name !in known }?.forEach {
                Log.i(TAG, "Removing unused voice model ${it.name}")
                it.deleteRecursively()
            }
        }
    }

    fun isInstalled(pkg: VoicePackage): Boolean = File(dirFor(pkg), MARKER).exists()

    fun files(pkg: VoicePackage): ModelFiles? =
        if (isInstalled(pkg)) ModelLayout.find(dirFor(pkg)) else null

    suspend fun download(pkg: VoicePackage) = withContext(Dispatchers.IO) {
        val archive = File(cache, "${pkg.id}.tar.bz2")
        val target = dirFor(pkg)
        try {
            setProgress(pkg, 0f)
            fetch(pkg.archiveUrl, archive) { setProgress(pkg, it) }
            setProgress(pkg, -1f)
            target.deleteRecursively()
            val context = coroutineContext
            archive.inputStream().use { ModelArchive.unpackTarBz2(it, target) { !context.isActive } }
            if (ModelLayout.find(target) == null) throw IOException("The downloaded voice is incomplete")
            File(target, MARKER).writeText(pkg.archiveUrl)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        } finally {
            archive.delete()
            mutableProgress.update { it - pkg.id }
            mutableInstalled.value = scanInstalled()
        }
    }

    fun delete(pkg: VoicePackage) {
        dirFor(pkg).deleteRecursively()
        mutableInstalled.value = scanInstalled()
    }

    private fun dirFor(pkg: VoicePackage) = File(root, pkg.id)

    private fun scanInstalled(): Set<String> =
        VoiceCatalog.packages.filter { File(dirFor(it), MARKER).exists() }.map { it.id }.toSet()

    private fun setProgress(pkg: VoicePackage, value: Float) =
        mutableProgress.update { it + (pkg.id to value) }

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

    private companion object {
        const val TAG = "VoiceModels"
        const val MARKER = ".installed"
    }
}
