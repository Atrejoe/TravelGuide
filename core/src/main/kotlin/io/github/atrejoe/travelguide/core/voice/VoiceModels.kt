package io.github.atrejoe.travelguide.core.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The files sherpa-onnx needs to run a downloaded voice model. */
data class ModelFiles(
    val model: File,
    val tokens: File,
    /** espeak-ng phonemizer data, used by Piper and Kokoro. */
    val dataDir: File,
    /** Speaker embeddings; only multi-speaker models such as Kokoro have them. */
    val voices: File?,
)

object ModelLayout {
    /**
     * Locates the model files below [root]. Hidden files (such as macOS `._` metadata that
     * archives sometimes carry) are ignored, and when several `.onnx` files exist the largest one
     * is the acoustic model.
     */
    fun find(root: File): ModelFiles? {
        if (!root.isDirectory) return null
        val files = root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { !it.name.startsWith(".") }
            .toList()
        val model = files.filter { it.isFile && it.name.endsWith(".onnx") }.maxByOrNull { it.length() } ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null
        val dataDir = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
        val voices = files.firstOrNull { it.isFile && it.name == "voices.bin" }
        return ModelFiles(model, tokens, dataDir, voices)
    }
}

object ModelArchive {
    /**
     * Unpacks a `.tar.bz2` model archive into [target], dropping the archive's top-level folder
     * (and a leading `./`) and refusing entries that would escape [target].
     */
    fun unpackTarBz2(input: InputStream, target: File, isCancelled: () -> Boolean = { false }) {
        val root = target.canonicalFile.apply { mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered())).use { tar ->
            while (true) {
                if (isCancelled()) throw IOException("Cancelled")
                val entry = tar.nextEntry ?: break
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isEmpty()) continue
                val out = File(root, relative).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    throw IOException("Refusing to unpack ${entry.name}")
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }
}
