package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.interest.InterestClassifier
import io.github.atrejoe.travelguide.core.lang.Dutch
import io.github.atrejoe.travelguide.core.lang.Heritage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceFacts
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.PlaceStoryWriter
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.SectionKind
import io.github.atrejoe.travelguide.core.story.SentenceSplitter
import io.github.atrejoe.travelguide.core.story.StoryWriter
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stories from the Dutch Wikipedia, told in Dutch (issue #68). */
class DutchStoryTest {
    private val article = """
        Amersfoort is een stad en gemeente in de Nederlandse provincie Utrecht. De stad werd in 1259 een stad en kreeg toen stadsrechten.

        == Naam ==
        De naam Amersfoort komt van de Amer, de oude naam van de Eem, en een voorde: een doorwaadbare plaats in de rivier.

        == Geschiedenis ==
        In de middeleeuwen groeide de stad rond de Onze-Lieve-Vrouwetoren. De toren is met ruim 98 meter een van de hoogste kerktorens van het land. In 1787 werd de kerk naast de toren verwoest door een ontploffing van buskruit. De beroemde Koppelpoort uit omstreeks 1400 is een land- en waterpoort tegelijk.

        == Demografie ==
        Op 1 januari 2024 telde de gemeente 160.000 inwoners. De bevolkingsdichtheid is 2.500 inwoners per km².

        == Stedenbanden ==
        Amersfoort heeft een stedenband met Ahmednagar.
    """.trimIndent()

    private val amersfoort = Poi(
        pageId = 1, title = "Amersfoort", point = GeoPoint(52.156, 5.389), type = PoiType.CITY,
        description = "stad en gemeente in Nederland", lang = "nl",
    )

    @Test
    fun `Dutch section titles are recognised`() {
        val kinds = ArticleParser.parse(article, Dutch).associate { it.title to it.kind }
        assertEquals(SectionKind.NAME, kinds["Naam"])
        assertEquals(SectionKind.HISTORY, kinds["Geschiedenis"])
        assertEquals(SectionKind.SKIP, kinds["Demografie"])
        assertEquals(SectionKind.SKIP, kinds["Stedenbanden"])
    }

    @Test
    fun `a Dutch article makes a Dutch story without statistics`() {
        val story = StoryWriter(Random(3)).write(amersfoort, ArticleParser.parse(article, Dutch), maxSentences = 5)
        val text = story.joinToString(" ")
        assertTrue("voorde" in text, "the name's origin is a good story: $story")
        assertTrue("verwoest" in text || "hoogste" in text, "remarkable history is told: $story")
        assertFalse("160.000" in text, "population figures are not story material")
        assertFalse("Ahmednagar" in text)
    }

    @Test
    fun `sentences are split after Dutch abbreviations only where a sentence ends`() {
        assertEquals(
            listOf("De burcht werd rond 50 v.Chr. gebouwd door de Romeinen.", "Later kwam er een kerk."),
            SentenceSplitter.split("De burcht werd rond 50 v.Chr. gebouwd door de Romeinen. Later kwam er een kerk.", Dutch),
        )
        assertEquals(
            listOf("Er staan o.a. een molen en een kerk.", "De molen is oud."),
            SentenceSplitter.split("Er staan o.a. een molen en een kerk. De molen is oud.", Dutch),
        )
    }

    @Test
    fun `the engine tells a Dutch place in Dutch`() = runTest {
        val start = GeoPoint(52.0, 5.0)
        val tower = Poi(
            pageId = 7, title = "Lange Jan", point = GeoMath.destination(GeoMath.destination(start, 0.0, 300.0), 270.0, 120.0),
            type = PoiType.LANDMARK, lang = "nl",
            extract = "De Lange Jan is de oudste toren van de stad. Volgens de legende werd hij in één nacht gebouwd door monniken. ".repeat(3),
        )
        val engine = GuideEngine({ _, _ -> listOf(tower) }, random = Random(1))
        for (s in 0..30) engine.onLocation(LocationFix(GeoMath.destination(start, 0.0, 1.4 * s), s * 1000L, 1.4))
        engine.refreshIfNeeded(30_000)
        val narration = assertNotNull(engine.nextNarration(30_000))
        val opener = narration.sentences.first()
        assertTrue(Regex("Lange Jan").containsMatchIn(opener) && Regex("\\b(ligt|Kijk|vind je)\\b").containsMatchIn(opener), opener)
        assertTrue(Regex("meter").containsMatchIn(opener), opener)
    }

    @Test
    fun `places without an article are described in Dutch`() {
        val mill = Poi(
            pageId = -5, title = "De Hoop", point = GeoPoint(52.0, 5.0), type = PoiType.LANDMARK, lang = "nl",
            description = "windmolen in Elburg",
            facts = PlaceFacts(
                kind = PlaceKind.WINDMILL, description = "windmolen in Elburg",
                since = HistoricDate(1488, HistoricDate.Precision.YEAR), architects = listOf("Jan de Molenaar", "Piet Bouwman"),
                heritage = Dutch.heritage(Heritage.Rijksmonument),
            ),
        )
        val story = PlaceStoryWriter(Random(0), currentYear = 2026).write(mill, maxSentences = 6)
        assertEquals("Het is een windmolen in Elburg.", story.first())
        assertTrue(story.any { "1488" in it && it.startsWith("Het ") }, "$story")
        assertTrue(story.any { "Jan de Molenaar en Piet Bouwman" in it }, "$story")
        assertTrue(story.any { "een rijksmonument" in it }, "$story")
        assertEquals("katholieke kerk", Dutch.withDenomination("roman_catholic", Dutch.placeNoun(PlaceKind.CHURCH)))
        assertEquals("Het is het stadhuis van Utrecht.", Dutch.theOne(PlaceKind.TOWN_HALL, "stadhuis van Utrecht"))
    }

    @Test
    fun `Dutch distances, directions and interests`() {
        assertEquals("800 meter", Dutch.distance(790.0))
        assertEquals("2,5 kilometer", Dutch.distance(2_480.0))
        assertEquals("zuidwesten", Dutch.compassName(220.0))
        assertTrue(Interest.HISTORY in InterestClassifier.classify(amersfoort.copy(extract = "Het kasteel werd in de middeleeuwen gebouwd.")))
        assertTrue(Interest.PLACES in InterestClassifier.classify(amersfoort.copy(type = PoiType.UNKNOWN, description = "dorp in Gelderland")))
        assertEquals(Dutch, StoryLanguages.of("nl"))
        assertEquals("en", StoryLanguages.of("xx").code)
    }
}
