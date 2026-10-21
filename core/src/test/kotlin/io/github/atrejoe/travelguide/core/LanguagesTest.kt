package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.lang.French
import io.github.atrejoe.travelguide.core.lang.German
import io.github.atrejoe.travelguide.core.lang.Heritage
import io.github.atrejoe.travelguide.core.lang.Italian
import io.github.atrejoe.travelguide.core.lang.Portuguese
import io.github.atrejoe.travelguide.core.lang.Spanish
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceFacts
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.PlaceStoryWriter
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import io.github.atrejoe.travelguide.core.story.SentenceSplitter
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every story language is complete and tells stories in its own words (issue #68). */
class LanguagesTest {
    private val title = "Torre Nova"

    /** A short article per language: an intro, a history section and a section to skip. */
    private val articles = mapOf(
        "de" to "Der Torre Nova ist ein Turm.\n== Geschichte ==\nDer Turm wurde 1488 erbaut und im 17. Jahrhundert durch einen Brand zerstört. Der Legende nach lebte dort ein Riese.\n== Einwohner ==\nDer Ort hat 2.000 Einwohner.",
        "fr" to "La Torre Nova est une tour.\n== Histoire ==\nLa tour a été bâtie en 1488 et détruite par un incendie au XVIIe siècle. Selon la légende, un géant y vivait.\n== Démographie ==\nLa commune compte 2 000 habitants.",
        "es" to "La Torre Nova es una torre.\n== Historia ==\nLa torre se construyó en 1488 y fue destruida por un incendio en el siglo XVII. Según la leyenda, allí vivía un gigante.\n== Demografía ==\nEl municipio tiene 2000 habitantes.",
        "it" to "La Torre Nova è una torre.\n== Storia ==\nLa torre fu costruita nel 1488 e distrutta da un incendio nel XVII secolo. Secondo la leggenda vi abitava un gigante.\n== Società ==\nIl comune conta 2000 abitanti.",
        "pt" to "A Torre Nova é uma torre.\n== História ==\nA torre foi construída em 1488 e destruída por um incêndio no século XVII. Segundo a lenda, ali vivia um gigante.\n== Demografia ==\nO município tem 2000 habitantes.",
    )

    private val others: List<StoryLanguage> = listOf(German, French, Spanish, Italian, Portuguese)

    @Test
    fun `every language is listed once and found by its code`() {
        assertEquals(listOf("en", "nl", "de", "fr", "es", "it", "pt"), StoryLanguages.all.map { it.code })
        others.forEach { assertEquals(it, StoryLanguages.of(it.code)) }
    }

    @Test
    fun `every language has every phrase`() {
        val positions = listOf(
            RelativePosition.Here, RelativePosition.Ahead(800.0), RelativePosition.Side(left = true, 2_400.0),
            RelativePosition.Behind(300.0), RelativePosition.Compass(95.0, 5_000.0),
        )
        for (language in StoryLanguages.all) {
            assertEquals(8, language.compassNames.size, language.name)
            assertTrue(language.distance(2_480.0).contains("2"), language.name)
            for (relative in positions) {
                assertTrue(language.afieldOpeners(title, relative).all { title in it }, "${language.name}: $relative")
                assertTrue(language.where(relative).isNotBlank())
            }
            assertTrue(language.hereOpeners(title, area = true, flying = false).all { title in it })
            assertTrue(language.sideOpeners(title, left = false, distance = language.distance(700.0), window = true).all { title in it })
            assertTrue(language.transitions(title, language.where(positions[1]), mentioned = true).all { title in it })
            for (kind in PlaceKind.entries) assertTrue(language.placeNoun(kind).isNotBlank(), "${language.name}: $kind")
            for (kind in SectionKind.entries.filter { it != SectionKind.INTRO && it != SectionKind.OTHER && it != SectionKind.SKIP }) {
                assertTrue(language.sectionRules.any { it.first == kind }, "${language.name} has no titles for $kind")
            }
        }
    }

    @Test
    fun `stories in other languages skip statistics and tell the legend`() {
        for (language in others) {
            val poi = Poi(1, title, GeoPoint(45.0, 9.0), PoiType.LANDMARK, lang = language.code)
            val sections = ArticleParser.parse(articles.getValue(language.code), language)
            assertEquals(SectionKind.HISTORY, sections[1].kind, language.name)
            assertEquals(SectionKind.SKIP, sections[2].kind, language.name)
            val story = io.github.atrejoe.travelguide.core.story.StoryWriter(Random(1)).write(poi, sections, maxSentences = 4)
            val text = story.joinToString(" ")
            assertTrue("1488" in text, "${language.name}: $story")
            assertTrue("2000" !in text && "2.000" !in text && "2 000" !in text, "${language.name}: $story")
        }
    }

    @Test
    fun `the engine tells a place in the language of its article`() = runTest {
        for (language in others) {
            val start = GeoPoint(45.0, 9.0)
            val tower = Poi(
                7, title, GeoMath.destination(GeoMath.destination(start, 0.0, 300.0), 90.0, 120.0), PoiType.LANDMARK,
                lang = language.code, extract = articles.getValue(language.code).lines().first().repeat(6),
            )
            val engine = GuideEngine({ _, _ -> listOf(tower) }, random = Random(2))
            for (s in 0..30) engine.onLocation(LocationFix(GeoMath.destination(start, 0.0, 1.4 * s), s * 1000L, 1.4))
            engine.refreshIfNeeded(30_000)
            val narration = assertNotNull(engine.nextNarration(30_000), language.name)
            val opener = narration.sentences.first()
            assertTrue(title in opener, "${language.name}: $opener")
            assertTrue(language.distance(400.0).substringAfterLast(' ') in opener, "${language.name}: $opener")
            assertEquals(TravelMode.WALKING, engine.motion.state?.mode)
        }
    }

    @Test
    fun `places without an article say what they are, in a gender that fits`() {
        for (language in others) {
            val mill = Poi(
                -5, "De Hoop", GeoPoint(52.0, 5.0), PoiType.LANDMARK, lang = language.code,
                facts = PlaceFacts(
                    kind = PlaceKind.WINDMILL, since = HistoricDate(1450, HistoricDate.Precision.CENTURY),
                    architects = listOf("Anna", "Bruno"), heritage = language.heritage(Heritage.Listed),
                ),
            )
            val story = PlaceStoryWriter(Random(0), currentYear = 2026).write(mill, maxSentences = 6)
            assertTrue(language.placeNoun(PlaceKind.WINDMILL) in story.first(), "${language.name}: $story")
            assertTrue(story.any { "Anna" in it && "Bruno" in it }, "${language.name}: $story")
            // Centuries are spelled out, so every voice reads them right.
            assertTrue(story.none { Regex("\\b15").containsMatchIn(it) }, "${language.name}: $story")
        }
    }

    @Test
    fun `a German ordinal dot doesn't end a sentence`() {
        assertEquals(
            listOf("Die Burg stammt aus dem 15. Jahrhundert und wurde 1488 erweitert.", "Danach verfiel sie."),
            SentenceSplitter.split("Die Burg stammt aus dem 15. Jahrhundert und wurde 1488 erweitert. Danach verfiel sie.", German),
        )
        assertEquals(listOf("Sie wurde 1488 gebaut.", "Danach kam der Brand."), SentenceSplitter.split("Sie wurde 1488 gebaut. Danach kam der Brand.", German))
    }
}
