package io.github.atrejoe.travelguide.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpServer
import io.github.atrejoe.travelguide.core.enhance.ClaudeModel
import io.github.atrejoe.travelguide.core.enhance.ClaudeStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.EnhanceOutcome
import io.github.atrejoe.travelguide.core.enhance.MemoryStoryStore
import io.github.atrejoe.travelguide.core.enhance.RetoldStory
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancement
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.StoryRequest
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.RelativePosition
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryEnhancementTest {
    private val bavo = Poi(
        pageId = 42, title = "Grote Kerk, Haarlem", point = GeoPoint(52.3811, 4.6373),
        description = "church in Haarlem", extract = "The Grote Kerk is a Protestant church. Mozart played its organ in 1766.",
    )
    private val narration = Narration(
        bavo,
        listOf(
            "On your left rises the Grote Kerk.",
            "It is a Protestant church.",
            "Mozart played its organ.",
            "A little further along, ahead, you'll find Teylers Museum.",
            "It is the oldest museum in the Netherlands.",
        ),
        RelativePosition.Side(left = true, 200.0),
        bodySize = 2,
    )
    private val interests = mapOf(Interest.HISTORY to 2.0, Interest.NATURE to 0.0, Interest.CULTURE to 1.0)

    private class FakeEnhancer(var answer: suspend (StoryRequest) -> RetoldStory) : StoryEnhancer {
        override val maxCostUsd = 0.05
        val requests = mutableListOf<StoryRequest>()
        override suspend fun retell(request: StoryRequest): RetoldStory {
            requests += request
            return answer(request)
        }
    }

    private val retold = RetoldStory("In 1766 a ten-year-old Mozart played its organ. The church still stands.", 0.02)

    @Test
    fun `retells only the part about the place itself`() = runTest {
        val fake = FakeEnhancer { retold }
        val enhancement = StoryEnhancement(fake, MemoryStoryStore(), monthlyBudgetUsd = 1.0)

        val story = enhancement.enhance(narration, TravelMode.WALKING, interests)

        assertEquals(
            listOf(
                "On your left rises the Grote Kerk.",
                "In 1766 a ten-year-old Mozart played its organ.",
                "The church still stands.",
                "A little further along, ahead, you'll find Teylers Museum.",
                "It is the oldest museum in the Netherlands.",
            ),
            story.sentences,
        )
        assertEquals(2, story.bodySize)
        assertEquals(EnhanceOutcome.RETOLD, enhancement.lastOutcome)
        val request = fake.requests.single()
        assertEquals("On your left rises the Grote Kerk.", request.opener)
        assertEquals("left · 200 m", request.where)
        assertEquals(listOf(Interest.HISTORY, Interest.CULTURE), request.interests)
        assertTrue("Mozart played its organ in 1766" in request.source)
        assertEquals(3, request.sentences)
    }

    @Test
    fun `a place is paid for only once`() = runTest {
        val fake = FakeEnhancer { retold }
        val store = MemoryStoryStore()
        val enhancement = StoryEnhancement(fake, store, monthlyBudgetUsd = 1.0, clock = { OCTOBER })

        val first = enhancement.enhance(narration, TravelMode.WALKING, interests)
        // Later, from the other side.
        val again = narration.copy(sentences = listOf("On your right is the Grote Kerk.") + narration.sentences.drop(1))
        val second = enhancement.enhance(again, TravelMode.DRIVING, interests)

        assertEquals(1, fake.requests.size)
        assertEquals(EnhanceOutcome.CACHED, enhancement.lastOutcome)
        assertEquals(first.sentences.drop(1), second.sentences.drop(1))
        assertEquals("On your right is the Grote Kerk.", second.sentences.first())
        assertEquals(0.02, store.spent("2026-10"), 1e-9)
    }

    @Test
    fun `keeps the template story when the budget is spent`() = runTest {
        val fake = FakeEnhancer { retold }
        val store = MemoryStoryStore().apply { addSpent("2026-10", 0.97) }
        val enhancement = StoryEnhancement(fake, store, monthlyBudgetUsd = 1.0, clock = { OCTOBER })

        assertEquals(narration, enhancement.enhance(narration, TravelMode.WALKING, interests))
        assertEquals(EnhanceOutcome.OVER_BUDGET, enhancement.lastOutcome)
        assertTrue(fake.requests.isEmpty())

        // A new month, a new budget.
        val november = StoryEnhancement(fake, store, monthlyBudgetUsd = 1.0, clock = { OCTOBER + 31L * 24 * 3600_000 })
        november.enhance(narration, TravelMode.WALKING, interests)
        assertEquals(EnhanceOutcome.RETOLD, november.lastOutcome)
    }

    @Test
    fun `falls back on errors and timeouts`() = runTest {
        val fake = FakeEnhancer { error("401 invalid x-api-key") }
        val store = MemoryStoryStore()
        val enhancement = StoryEnhancement(fake, store, monthlyBudgetUsd = 1.0, timeoutMillis = 1_000, clock = { OCTOBER })

        assertEquals(narration, enhancement.enhance(narration, TravelMode.WALKING, interests))
        assertEquals(EnhanceOutcome.FAILED, enhancement.lastOutcome)
        assertEquals(0.0, store.spent("2026-10"))

        fake.answer = { awaitCancellation() }
        assertEquals(narration, enhancement.enhance(narration, TravelMode.WALKING, interests))
        assertEquals(EnhanceOutcome.FAILED, enhancement.lastOutcome)
        assertNull(enhancement.lastError)
        // The cut-off call may have been billed.
        assertEquals(0.05, store.spent("2026-10"), 1e-9)
    }

    @Test
    fun `skips places without text to retell`() = runTest {
        val fake = FakeEnhancer { retold }
        val enhancement = StoryEnhancement(fake, MemoryStoryStore(), monthlyBudgetUsd = 1.0)
        val building = narration.copy(poi = bavo.copy(pageId = -7, extract = ""))

        assertEquals(building, enhancement.enhance(building, TravelMode.WALKING, interests))
        assertEquals(EnhanceOutcome.SKIPPED, enhancement.lastOutcome)
    }

    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    /** A stand-in for the Messages API that answers every request with [body]. */
    private fun claude(status: Int, body: String, requests: MutableList<String>): ClaudeStoryEnhancer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        s.createContext("/v1/messages") { exchange ->
            requests += exchange.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        val client = AnthropicOkHttpClient.builder()
            .baseUrl("http://127.0.0.1:${s.address.port}")
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        return ClaudeStoryEnhancer(client, ClaudeModel.HAIKU)
    }

    private fun message(stopReason: String, text: String) = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"$text"}],
         "stop_reason":"$stopReason","stop_sequence":null,
         "usage":{"input_tokens":1000,"output_tokens":400}}
    """.trimIndent()

    private val request = StoryRequest(
        title = "Grote Kerk, Haarlem", description = "church in Haarlem", source = bavo.extract,
        opener = "On your left rises the Grote Kerk.", mode = TravelMode.WALKING, where = "left · 200 m",
        interests = listOf(Interest.HISTORY), sentences = 3, lang = "en",
    )

    @Test
    fun `asks Claude and reads its story and cost`() = runBlocking {
        val requests = mutableListOf<String>()
        val enhancer = claude(200, message("end_turn", "Mozart played here."), requests)

        val story = enhancer.retell(request)

        assertEquals("Mozart played here.", story.text)
        assertEquals((1000 * 0.10 + 400 * 0.50) / 1_000_000, story.costUsd, 1e-12)
        val sent = requests.single()
        assertTrue("\"model\":\"claude-haiku-5-5\"" in sent, sent)
        assertTrue("\"effort\":\"low\"" in sent, sent)
        assertTrue("Grote Kerk, Haarlem" in sent && "On your left rises the Grote Kerk." in sent, sent)
    }

    @Test
    fun `a refusal or a cut-off story is a failure`() = runBlocking {
        val refused = claude(200, message("refusal", ""), mutableListOf())
        assertFailsWith<IllegalStateException> { refused.retell(request) }
        stopServer()
        val cut = claude(200, message("max_tokens", "Mozart played"), mutableListOf())
        assertFailsWith<IllegalStateException> { cut.retell(request) }
    }

    @Test
    fun `API errors are thrown`() = runBlocking {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val enhancer = claude(401, body, mutableListOf())
        assertFailsWith<Exception> { enhancer.retell(request) }
        Unit
    }

    private companion object {
        /** 20 October 2026. */
        const val OCTOBER = 1_792_454_400_000L
    }
}
