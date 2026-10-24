package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiScorer
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.StoryWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stories, well told: whole passages in order, and events before administration (issue #78). */
class StorytellingTest {
    private val point = GeoPoint(44.2565, 4.6476)

    /** An article about an event, in the shape of Wikipedia's (a summary, not its text). */
    private val poisoning = """
        In August 1951 a mass poisoning struck the small town of Pont-Saint-Esprit in the south of France. Hundreds of people fell ill and several died.
        The cause has never been settled.

        == Event ==
        It began on 16 August with nausea and chills. Within days some victims saw visions of flames and animals. One man tried to drown himself, convinced that snakes were eating his belly. Another jumped from a window, shouting that he could fly.

        == Investigation ==
        Suspicion fell on the bread from one bakery. Investigators first blamed ergot, a fungus on rye that had caused outbreaks of madness in the Middle Ages. Others pointed to mercury used to treat the grain.

        == References ==
        Gabbai, L. (1951). British Medical Journal.
    """.trimIndent()

    private val event = Poi(
        2, "1951 Pont-Saint-Esprit mass poisoning", point, PoiType.UNKNOWN,
        description = "Mass poisoning in France", extract = poisoning.substringBefore("=="),
    )

    private val town = Poi(
        1, "Pont-Saint-Esprit", point, PoiType.CITY, population = 10_000,
        description = "commune in Gard, France",
        extract = "Pont-Saint-Esprit is a commune in the Gard department in the Occitanie region of southern France. " +
            "It lies on the right bank of the Rhône. The commune has a population of about 10,000.",
    )

    @Test
    fun `an event is told as it happened, in its own order`() {
        val story = StoryWriter(Random(1)).write(event, ArticleParser.parse(poisoning), maxSentences = 7)
        val text = story.joinToString(" ")
        // No "It is a mass poisoning in France": the article's own opening says what happened.
        assertTrue(story.first().startsWith("In August 1951"), "story: $story")
        assertTrue("snakes" in text && "fly" in text, "the heart of the story is told: $story")
        assertFalse("British Medical Journal" in text)
        // Sentences that lean on the one before follow it.
        val began = story.indexOfFirst { it.startsWith("It began") }
        assertTrue(began > 0 && "visions" in story[began + 1], "story: $story")
    }

    @Test
    fun `a passage is told whole rather than picked apart`() {
        val story = StoryWriter(Random(1)).write(event, ArticleParser.parse(poisoning), maxSentences = 7)
        val article = ArticleParser.parse(poisoning).joinToString(" ") { it.text }.replace('\n', ' ')
        // The told sentences of the event appear in the article in the same order, one after another.
        val told = story.filter { it in article }
        val positions = told.map { article.indexOf(it) }
        assertEquals(positions.sorted(), positions, "story: $story")
    }

    @Test
    fun `events count as stories, administrative units hardly`() {
        assertTrue(StoryWriter.isEvent(event))
        assertFalse(StoryWriter.isEvent(town))
        assertTrue(PoiScorer.prominence(event) > PoiScorer.prominence(town), "${PoiScorer.prominence(event)} vs ${PoiScorer.prominence(town)}")
        // A town whose lead only says where it lies tells little rather than its statistics.
        val story = StoryWriter(Random(1)).write(town, ArticleParser.parse(town.extract), maxSentences = 7)
        assertFalse(story.any { "10,000" in it }, "story: $story")
    }

    @Test
    fun `a language model gets the story-worthy part of the article, best first`() {
        val source = StoryWriter().source(event, ArticleParser.parse(poisoning))
        assertTrue(source.startsWith("In August 1951"), source)
        assertTrue(source.indexOf("snakes") in 0 until source.indexOf("ergot"), source)
        assertFalse("British Medical Journal" in source)
    }
}
