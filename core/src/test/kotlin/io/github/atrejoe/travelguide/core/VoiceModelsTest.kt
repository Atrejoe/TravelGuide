package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.voice.ModelArchive
import io.github.atrejoe.travelguide.core.voice.ModelLayout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceModelsTest {
    @TempDir
    lateinit var tmp: File

    private fun tarBz2(vararg entries: Pair<String, String?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                val entry = TarArchiveEntry(name)
                if (content != null) entry.size = content.toByteArray().size.toLong()
                tar.putArchiveEntry(entry)
                content?.let { tar.write(it.toByteArray()) }
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `unpacks a Kokoro style archive and finds its files`() {
        val archive = tarBz2(
            "./kokoro-int8-en-v0_19/" to null,
            "./kokoro-int8-en-v0_19/model.int8.onnx" to "x".repeat(100),
            "./kokoro-int8-en-v0_19/._model.int8.onnx" to "junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk-junk",
            "./kokoro-int8-en-v0_19/voices.bin" to "v",
            "./kokoro-int8-en-v0_19/tokens.txt" to "t",
            "./kokoro-int8-en-v0_19/espeak-ng-data/" to null,
            "./kokoro-int8-en-v0_19/espeak-ng-data/en_dict" to "d",
        )
        val target = File(tmp, "kokoro")
        ModelArchive.unpackTarBz2(ByteArrayInputStream(archive), target)

        val files = assertNotNull(ModelLayout.find(target))
        assertEquals("model.int8.onnx", files.model.name)
        assertEquals("voices.bin", files.voices?.name)
        assertTrue(File(files.dataDir, "en_dict").exists())
    }

    @Test
    fun `piper layout has no voices file and picks the largest onnx`() {
        val archive = tarBz2(
            "vits-piper-en_US-ryan-high/en_US-ryan-high.onnx" to "m".repeat(50),
            "vits-piper-en_US-ryan-high/small-helper.onnx" to "h",
            "vits-piper-en_US-ryan-high/en_US-ryan-high.onnx.json" to "{}",
            "vits-piper-en_US-ryan-high/tokens.txt" to "t",
            "vits-piper-en_US-ryan-high/espeak-ng-data/phontab" to "p",
        )
        val target = File(tmp, "piper")
        ModelArchive.unpackTarBz2(ByteArrayInputStream(archive), target)

        val files = assertNotNull(ModelLayout.find(target))
        assertEquals("en_US-ryan-high.onnx", files.model.name)
        assertNull(files.voices)
    }

    @Test
    fun `incomplete model is not reported`() {
        File(tmp, "broken").mkdirs()
        File(tmp, "broken/model.onnx").writeText("m")
        assertNull(ModelLayout.find(File(tmp, "broken")))
    }

    @Test
    fun `path traversal is refused`() {
        val archive = tarBz2("root/../../evil.txt" to "boom")
        assertFailsWith<IOException> {
            ModelArchive.unpackTarBz2(ByteArrayInputStream(archive), File(tmp, "safe"))
        }
        assertTrue(!File(tmp, "evil.txt").exists())
    }
}
