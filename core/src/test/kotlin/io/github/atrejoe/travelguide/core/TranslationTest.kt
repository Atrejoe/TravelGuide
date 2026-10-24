package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.lang.CountryLanguages
import io.github.atrejoe.travelguide.core.lang.Dutch
import io.github.atrejoe.travelguide.core.lang.MultilingualPoiSource
import io.github.atrejoe.travelguide.core.lang.Translator
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.wiki.PoiSource
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places only another Wikipedia knows, translated into the story language (issue #71). */
class TranslationTest {
    private val start = GeoPoint(45.0, 9.0)
    private val near = GeoMath.destination(GeoMath.destination(start, 0.0, 300.0), 90.0, 120.0)
    private val english = "The tower is the oldest in the region. According to legend, a giant built it in one night. ".repeat(4)

    private fun poi(id: Long, title: String, lang: String, item: String?, extract: String = english) =
        Poi(id, title, near, PoiType.LANDMARK, lang = lang, wikidataId = item, extract = extract)

    /** Pretends to translate by marking each sentence. */
    private val fakeTranslator = Translator { texts, from, to -> texts.map { "[$from→$to] $it" } }

    private fun walkingEngine(places: List<Poi>, translator: Translator?) = GuideEngine({ _, _ -> places }, random = Random(1)).apply {
        language = Dutch
        this.translator = translator
        for (s in 0..30) onLocation(LocationFix(GeoMath.destination(start, 0.0, 1.4 * s), s * 1000L, 1.4))
    }

    @Test
    fun `other Wikipedias add only the places the story language doesn't have`() = runTest {
        val primary = PoiSource { _, _ -> listOf(poi(1, "Toren", "nl", "Q1")) }
        val english = PoiSource { _, _ -> listOf(poi(10, "Tower", "en", "Q1"), poi(11, "Old Mill", "en", "Q2")) }
        val broken = PoiSource { _, _ -> throw IOException("offline") }
        val italian = PoiSource { _, _ -> listOf(poi(20, "Mulino Vecchio", "it", "Q2"), poi(21, "Fontana", "it", null)) }
        val found = MultilingualPoiSource(primary) { listOf(english, broken, italian) }.nearby(start, 1_000)
        assertEquals(listOf("Toren" to "nl", "Old Mill" to "en", "Fontana" to "it"), found.map { it.title to it.lang })
    }

    @Test
    fun `a place only in English is told in Dutch, its article translated`() = runTest {
        val engine = walkingEngine(listOf(poi(10, "Torre", "en", "Q1")), fakeTranslator)
        engine.refreshIfNeeded(30_000)
        val narration = assertNotNull(engine.nextNarration(30_000))
        assertEquals("nl", narration.lang)
        assertTrue("meter" in narration.sentences.first() && "[" !in narration.sentences.first(), narration.sentences.first())
        val body = narration.sentences.drop(1).take(narration.bodySize)
        assertTrue(body.isNotEmpty() && body.all { it.startsWith("[en→nl] ") }, "$body")
        assertTrue(narration.sentenceLangs.all { it == "nl" })
    }

    @Test
    fun `without a translator, or when it fails, only places in the story language are told`() = runTest {
        val englishOnly = poi(10, "Torre", "en", "Q1")
        assertNull(walkingEngine(listOf(englishOnly), translator = null).run { refreshIfNeeded(30_000); nextNarration(30_000) })
        val failing = Translator { _, _, _ -> throw IOException("model not downloaded") }
        assertNull(walkingEngine(listOf(englishOnly), failing).run { refreshIfNeeded(30_000); nextNarration(30_000) })

        val dutch = poi(1, "Toren", "nl", "Q2", extract = "De toren is de oudste van de streek. Volgens de legende bouwde een reus hem in één nacht. ".repeat(4))
        val narration = assertNotNull(walkingEngine(listOf(englishOnly, dutch), translator = null).run { refreshIfNeeded(30_000); nextNarration(30_000) })
        assertEquals("Toren", narration.poi.title)
    }

    @Test
    fun `the local language comes from the country`() {
        assertEquals("it", CountryLanguages.of("IT"))
        assertEquals("pt", CountryLanguages.of("br"))
        assertNull(CountryLanguages.of("xx"))
        val nominatim = io.github.atrejoe.travelguide.core.nav.NominatimClient({ "" })
        assertEquals("it", nominatim.parseCountry("""{"address":{"country":"Italia","country_code":"IT"}}"""))
        assertNull(nominatim.parseCountry("""{"error":"Unable to geocode"}"""))
        assertTrue("zoom=3" in nominatim.countryUrl(start))
    }
}
