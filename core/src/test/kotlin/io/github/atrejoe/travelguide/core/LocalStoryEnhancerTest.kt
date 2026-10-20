package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.enhance.Generated
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.core.enhance.LocalStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.MemoryStoryStore
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancement
import io.github.atrejoe.travelguide.core.enhance.StoryPrompt
import io.github.atrejoe.travelguide.core.enhance.StoryRequest
import io.github.atrejoe.travelguide.core.enhance.TextGenerator
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.RelativePosition
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalStoryEnhancerTest {
    private val request = StoryRequest(
        title = "Grote Kerk, Haarlem", description = "church in Haarlem", source = "Mozart played its organ in 1766.",
        opener = "On your left rises the Grote Kerk.", mode = TravelMode.WALKING, where = "left · 200 m",
        interests = listOf(Interest.HISTORY), sentences = 3, lang = "en",
    )

    @Test
    fun `sends the shared prompt, with the model's own suffix`() = runTest {
        var sent: Triple<String, String, Int>? = null
        val enhancer = LocalStoryEnhancer(
            { system, user, max -> sent = Triple(system, user, max); Generated("Mozart played here.", cutOff = false) },
            LocalModels.QWEN3,
        )

        val story = enhancer.retell(request)

        assertEquals("Mozart played here.", story.text)
        assertEquals(0.0, story.costUsd)
        val (system, user, max) = sent!!
        assertEquals(StoryPrompt.SYSTEM, system)
        assertTrue(user.startsWith("Place: Grote Kerk, Haarlem"))
        assertTrue(user.endsWith(" /no_think"), user)
        assertEquals(LocalStoryEnhancer.MAX_TOKENS, max)
    }

    @Test
    fun `drops thinking and a sentence cut off at the limit`() {
        assertEquals("Mozart played here.", LocalStoryEnhancer.clean(Generated("<think>\n\n</think>\n\nMozart played here.", false)))
        assertEquals("He was ten.", LocalStoryEnhancer.clean(Generated("He was ten. The organ", cutOff = true)))
        assertNull(LocalStoryEnhancer.clean(Generated("<think>Let me think about Mozart", cutOff = true)))
        assertNull(LocalStoryEnhancer.clean(Generated("   ", false)))
    }

    @Test
    fun `an empty story is a failure`() = runTest {
        val enhancer = LocalStoryEnhancer({ _, _, _ -> Generated("", false) }, LocalModels.GEMMA3)
        assertFailsWith<IllegalStateException> { enhancer.retell(request) }
    }

    @Test
    fun `gets a shorter source text, the template's highlights first`() = runTest {
        var user = ""
        val generator = TextGenerator { _, u, _ -> user = u; Generated("A story.", false) }
        val poi = Poi(pageId = 42, title = "Grote Kerk", point = GeoPoint(52.38, 4.63), extract = "Lead. ".repeat(1_000))
        val narration = Narration(
            poi, listOf("On your left rises the Grote Kerk.", "Mozart played its organ."),
            RelativePosition.Side(left = true, 200.0), bodySize = 1,
        )

        StoryEnhancement(LocalStoryEnhancer(generator, LocalModels.SMOLLM2), MemoryStoryStore(), Double.MAX_VALUE)
            .enhance(narration, TravelMode.WALKING, emptyMap())

        val source = user.substringAfter("<source>\n").substringBefore("\n</source>")
        assertTrue(source.startsWith("Mozart played its organ."), source)
        assertEquals(LocalStoryEnhancer.MAX_SOURCE_CHARS, source.length)
    }

    @Test
    fun `every model has a download`() {
        assertEquals(LocalModels.QWEN3, LocalModels.DEFAULT)
        assertEquals(LocalModels.all.size, LocalModels.all.map { it.id }.toSet().size)
        LocalModels.all.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/") && it.url.endsWith(".gguf"), it.url)
            assertTrue(it.sha256.orEmpty().matches(Regex("[0-9a-f]{64}")), "${it.name} is checked after downloading")
        }
        assertEquals(LocalModels.GEMMA3, LocalModels.byId("gemma3-1b-q4km"))
        assertEquals(LocalModels.DEFAULT, LocalModels.byId("gone"))
    }

    /** The exact prompt of the sample story, for the native check in CI (tools/llama-check). */
    @Test
    fun `writes the sample prompt for the native check`() {
        val dir = File("build/llama-check").apply { mkdirs() }
        File(dir, "system.txt").writeText(StoryPrompt.SYSTEM)
        File(dir, "user.txt").writeText(StoryPrompt.user(LocalStoryEnhancer.SAMPLE))
        LocalModels.all.forEach { File(dir, "${it.id}.suffix").writeText(it.userSuffix) }
        assertTrue(File(dir, "user.txt").readText().contains("Grote Kerk"))
    }
}
