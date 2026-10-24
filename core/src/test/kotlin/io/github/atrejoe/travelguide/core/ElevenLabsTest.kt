package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.voice.ElevenLabsClient
import io.github.atrejoe.travelguide.core.voice.ElevenLabsException
import io.github.atrejoe.travelguide.core.voice.ElevenLabsModel
import io.github.atrejoe.travelguide.core.voice.HttpRequest
import io.github.atrejoe.travelguide.core.voice.HttpResponse
import io.github.atrejoe.travelguide.core.voice.SpeechCache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ElevenLabs voices with the listener's own key (issue #75). */
class ElevenLabsTest {
    private val sent = mutableListOf<HttpRequest>()

    private fun client(vararg responses: Pair<Int, String>): ElevenLabsClient {
        val queue = ArrayDeque(responses.toList())
        return ElevenLabsClient("key-123") { request ->
            sent += request
            val (code, body) = queue.removeFirst()
            HttpResponse(code, body.toByteArray())
        }
    }

    @Test
    fun `speech is asked for as PCM, in the story language when the model takes one`() {
        val c = client()
        val flash = c.speechRequest("v1", "De toren.", ElevenLabsModel.FLASH, "nl", speed = 1.5f, previous = "Kijk.", next = null)
        assertEquals("POST", flash.method)
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/v1?output_format=pcm_24000", flash.url)
        assertEquals("key-123", flash.headers["xi-api-key"])
        val body = Json.parseToJsonElement(flash.body!!).jsonObject
        assertEquals("eleven_flash_v2_5", body.getValue("model_id").jsonPrimitive.content)
        assertEquals("nl", body.getValue("language_code").jsonPrimitive.content)
        assertEquals("Kijk.", body.getValue("previous_text").jsonPrimitive.content)
        assertTrue("next_text" !in body)
        // Clamped to what ElevenLabs accepts.
        assertEquals("1.2", body.getValue("voice_settings").jsonObject.getValue("speed").jsonPrimitive.content)

        val multi = Json.parseToJsonElement(c.speechRequest("v1", "x", ElevenLabsModel.MULTILINGUAL, "nl", 1f).body!!).jsonObject
        assertTrue("language_code" !in multi, "Multilingual v2 rejects a language code")
    }

    @Test
    fun `voices are read from every page, those verified in the language first`() = runTest {
        val c = client(
            200 to """{"voices":[{"voice_id":"a","name":"Rachel","labels":{"accent":"american","gender":"female","age":"young"},
                "verified_languages":[{"language":"en"}]}],"has_more":true,"next_page_token":"p2","total_count":2}""",
            200 to """{"voices":[{"voice_id":"b","name":"Daan","labels":{},"description":"Calm Dutch narrator",
                "verified_languages":[{"language":"nl"},{"language":"en"}]}],"has_more":false,"total_count":2}""",
        )
        val voices = c.voices("nl")
        assertEquals(listOf("Daan", "Rachel"), voices.map { it.name })
        assertEquals("American, female, young", voices[1].description)
        assertEquals("Calm Dutch narrator", voices[0].description)
        assertTrue(sent[1].url.endsWith("&next_page_token=p2"))
    }

    @Test
    fun `refusals say why`() = runTest {
        val quota = assertFailsWith<ElevenLabsException> {
            client(401 to """{"detail":{"status":"quota_exceeded","message":"This request exceeds your quota."}}""")
                .speech("v", "x", ElevenLabsModel.FLASH, "en", 1f)
        }
        assertEquals(ElevenLabsException.Reason.QUOTA_EXCEEDED, quota.reason)
        assertTrue(quota.permanent)
        assertEquals("This request exceeds your quota.", quota.message)
        val key = assertFailsWith<ElevenLabsException> { client(401 to """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""").usage() }
        assertEquals(ElevenLabsException.Reason.INVALID_KEY, key.reason)
        val busy = ElevenLabsClient.error(429, "Too many requests")
        assertEquals(ElevenLabsException.Reason.BUSY, busy.reason)
        assertTrue(!busy.permanent)
    }

    @Test
    fun `usage and audio are decoded`() = runTest {
        val usage = client(200 to """{"tier":"free","character_count":2500,"character_limit":10000,"next_character_count_reset_unix":1767225600}""").usage()
        assertEquals(7_500, usage.left)
        assertEquals(1767225600, usage.resetsAtUnix)
        val samples = ElevenLabsClient.pcm16ToFloat(byteArrayOf(0, 0, 0, 0x40, 0, 0x80.toByte(), 0xff.toByte(), 0x7f))
        assertContentEquals(floatArrayOf(0f, 0.5f, -1f, 32767 / 32768f), samples)
    }

    @Test
    fun `the cache keeps the newest speech within its size`() {
        val dir = Files.createTempDirectory("speech").toFile()
        val cache = SpeechCache(dir, maxBytes = 25)
        val a = SpeechCache.key("v", "m", 1f, "a")
        val b = SpeechCache.key("v", "m", 1f, "b")
        cache.put(a, ByteArray(10) { 1 })
        assertContentEquals(ByteArray(10) { 1 }, cache.get(a))
        dir.listFiles()!!.single().setLastModified(System.currentTimeMillis() - 10_000)
        cache.put(b, ByteArray(20))
        assertNull(cache.get(a), "the oldest goes first")
        assertEquals(20, cache.get(b)?.size)
        assertTrue(SpeechCache.key("v", "m", 0.95f, "a") != SpeechCache.key("v", "m", 1f, "a"))
        dir.deleteRecursively()
    }
}
