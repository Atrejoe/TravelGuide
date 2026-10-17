package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.SectionKind
import io.github.atrejoe.travelguide.core.story.StoryWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryTest {
    private val article = javaClass.getResource("/article-utrecht.txt")!!.readText()
    private val utrecht = Poi(
        pageId = 1, title = "Utrecht", point = GeoPoint(52.09, 5.12), type = PoiType.CITY,
        description = "City and municipality in the Netherlands",
    )

    @Test
    fun `parser classifies sections and inherits skip from parents`() {
        val sections = ArticleParser.parse(article)
        val kinds = sections.associate { it.title to it.kind }
        assertEquals(SectionKind.INTRO, kinds[""])
        assertEquals(SectionKind.NAME, kinds["Etymology"])
        assertEquals(SectionKind.HISTORY, kinds["Prehistory and Roman times"])
        assertEquals(SectionKind.HISTORY, kinds["Middle Ages"])
        assertEquals(SectionKind.SKIP, kinds["Population"])
        assertEquals(SectionKind.SKIP, kinds["Demographics"])
        assertEquals(SectionKind.CULTURE, kinds["Culture"])
        assertEquals(SectionKind.SKIP, kinds["Twin towns"])
    }

    @Test
    fun `story prefers anecdotes over statistics and starts with the name`() {
        val story = StoryWriter(Random(3)).write(utrecht, ArticleParser.parse(article), maxSentences = 5)
        val text = story.joinToString(" ")

        assertEquals("It is a city and municipality in the Netherlands.", story.first())
        assertFalse("361,924" in text, "population figures are not story material")
        assertFalse("54%" in text)
        assertFalse("twinned" in text)
        assertTrue("beyond the ford" in text, "the name's origin is a good story")
        val nameIndex = story.indexOfFirst { "beyond the ford" in it }
        val historyIndex = story.indexOfFirst { "1122" in it || "Roman fort" in it || "tornado" in it }
        assertTrue(historyIndex > nameIndex)
        // Identity line + 4 article sentences + at most 2 short lead-ins.
        assertTrue(story.size in 5..7, "story: $story")
    }

    @Test
    fun `identity line keeps proper adjectives and drops useless descriptions`() {
        val writer = StoryWriter()
        assertEquals("It is a Dutch village on the river Vecht.", writer.identityLine(utrecht.copy(description = "Dutch village on the river Vecht")))
        assertEquals("It is an archaeological site in Italy.", writer.identityLine(utrecht.copy(description = "Archaeological site in Italy")))
        assertEquals("It is a church in Utrecht, Netherlands.", writer.identityLine(utrecht.copy(description = "Church in Utrecht, Netherlands")))
        assertEquals(null, writer.identityLine(utrecht.copy(description = "Wikimedia disambiguation page")))
    }

    @Test
    fun `scores reward history and punish administration`() {
        val legend = StoryWriter.score("According to legend, the bells were cast by monks in 1250.", SectionKind.HISTORY, true)
        val stats = StoryWriter.score("The municipality has a population density of 3,600 per km2.", SectionKind.INTRO, true)
        assertTrue(legend > 1.0)
        assertTrue(stats < StoryWriter.MIN_SCORE)
    }
}
