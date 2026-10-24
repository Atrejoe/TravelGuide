package io.github.atrejoe.travelguide.core.places

import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.nav.NominatimClient
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.HistoricDate.Precision
import io.github.atrejoe.travelguide.core.story.PlaceFacts
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.PlaceStoryWriter
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.wiki.WikidataClient
import io.github.atrejoe.travelguide.core.wiki.WikipediaClient
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()

    private val overpassJson = resource("overpass-places.json")
    private val entitiesJson = resource("wikidata-entities.json")
    private val labelsJson = resource("wikidata-labels.json")
    private val center = GeoPoint(52.0908, 5.1205)

    private fun source(requests: MutableList<String> = mutableListOf(), wikidataDown: Boolean = false): OsmPlaceSource {
        val http = { url: String ->
            requests += url
            when {
                "overpass" in url -> overpassJson
                wikidataDown -> throw IOException("HTTP 503")
                "props=labels&" in url -> labelsJson
                else -> entitiesJson
            }
        }
        return OsmPlaceSource(OverpassClient(http), WikidataClient(http), currentYear = 2026)
    }

    @Test
    fun `buildings and institutions come with their facts, boring and bare places do not`() = runTest {
        val requests = mutableListOf<String>()
        val places = source(requests).places(center, 500).associateBy { it.title }

        assertEquals(setOf("Utrecht City Hall", "Sint-Catharinakerk", "Het Hoogt", "Dom Tower of Utrecht"), places.keys)

        val hall = places.getValue("Utrecht City Hall")
        val facts = assertNotNull(hall.facts)
        assertEquals(PlaceKind.TOWN_HALL, facts.kind)
        assertEquals(HistoricDate(1547, Precision.YEAR), facts.since, "the deprecated date is ignored")
        assertEquals(listOf("Joost Ritsema van Eck"), facts.architects)
        assertEquals(listOf("Neoclassical architecture"), facts.styles)
        assertEquals("a Rijksmonument, a listed national monument", facts.heritage)
        assertEquals("municipal council of Utrecht", facts.occupant)
        assertEquals("city hall of Utrecht, Netherlands", hall.description)
        assertEquals("Q2601", hall.wikidataId)
        assertEquals("https://www.wikidata.org/wiki/Q2601", hall.url)
        assertTrue(hall.pageId < 0)
        assertFalse(hall.hasArticle)
        val story = PlaceStoryWriter(Random(0), currentYear = 2026).write(hall, maxSentences = 6)
        assertEquals("It is the city hall of Utrecht, Netherlands.", story.first())
        assertTrue(story.any { "the municipal council of Utrecht" in it }, "$story")

        val church = places.getValue("Sint-Catharinakerk")
        assertEquals("Catholic church", church.description)
        assertEquals(HistoricDate(1450, Precision.CENTURY), church.facts?.since)
        assertEquals(listOf("Pieter de Swart"), church.facts?.architects)
        assertEquals("https://www.openstreetmap.org/node/1001", church.url)

        // A café counts only because it is a listed building.
        assertEquals(PlaceKind.HISTORIC_BUILDING, places.getValue("Het Hoogt").facts?.kind)

        val tower = places.getValue("Dom Tower of Utrecht")
        assertEquals("Dom Tower of Utrecht", tower.wikiTitle)
        assertTrue(tower.hasArticle)

        // One Overpass query, one batch of items, one batch of labels.
        assertEquals(3, requests.size)
        assertTrue("ids=Q2601%7CQ1404" in requests[1], requests[1])
    }

    @Test
    fun `without Wikidata the OpenStreetMap facts still make stories`() = runTest {
        val places = source(wikidataDown = true).places(center, 500).associateBy { it.title }
        val hall = assertNotNull(places["Stadhuis"])
        assertEquals("a Rijksmonument, a listed national monument", hall.facts?.heritage)
        assertEquals("town hall", hall.description)
        assertTrue("Sint-Catharinakerk" in places)
    }

    @Test
    fun `classifier keeps institutions and history, drops shops and stumbling stones`() {
        fun kind(vararg tags: Pair<String, String>) = PlaceClassifier.kind(mapOf("name" to "X", *tags))
        assertEquals(PlaceKind.TOWN_HALL, kind("amenity" to "townhall"))
        assertEquals(PlaceKind.MOSQUE, kind("amenity" to "place_of_worship", "religion" to "muslim"))
        assertEquals(PlaceKind.CATHEDRAL, kind("amenity" to "place_of_worship", "building" to "cathedral"))
        assertEquals(PlaceKind.PALACE, kind("historic" to "castle", "castle_type" to "palace"))
        assertEquals(PlaceKind.STATUE, kind("tourism" to "artwork", "artwork_type" to "statue"))
        assertEquals(PlaceKind.WINDMILL, kind("man_made" to "windmill"))
        assertEquals(PlaceKind.HISTORIC_BUILDING, kind("shop" to "books", "historic" to "building"))
        assertNull(kind("shop" to "books"))
        assertNull(kind("amenity" to "parking"))
        assertNull(kind("historic" to "memorial", "memorial" to "stolperstein"))
        assertNull(kind("man_made" to "tower", "tower:type" to "communication"))
        assertNull(PlaceClassifier.kind(mapOf("amenity" to "townhall")), "unnamed")
    }

    @Test
    fun `dates are read from OpenStreetMap and Wikidata`() {
        assertEquals(HistoricDate(1488, Precision.YEAR), HistoricDate.parseOsm("1488-05-01", 2026))
        assertEquals(HistoricDate(1600, Precision.YEAR), HistoricDate.parseOsm("~1600", 2026))
        assertEquals(HistoricDate(1600, Precision.YEAR), HistoricDate.parseOsm("1600..1650", 2026))
        assertEquals(HistoricDate(1480, Precision.DECADE), HistoricDate.parseOsm("1480s", 2026))
        assertEquals("the 15th century", HistoricDate.parseOsm("C15", 2026)?.sincePhrase)
        assertNull(HistoricDate.parseOsm("early 1500s", 2026))
        assertNull(HistoricDate.parseOsm("2090", 2026))
        assertEquals("in the 1480s", HistoricDate.parseWikidata("+1480-00-00T00:00:00Z", 8, 2026)?.inPhrase)
        assertEquals("the 15th century", HistoricDate.parseWikidata("+1500-00-00T00:00:00Z", 7, 2026)?.sincePhrase)
        assertEquals("the 21st century", HistoricDate(2001, Precision.CENTURY).sincePhrase)
        assertNull(HistoricDate.parseWikidata("-0050-00-00T00:00:00Z", 9, 2026))
    }

    @Test
    fun `facts become a history-first spoken story without raw tags`() {
        val poi = Poi(
            -1, "The Waag", GeoPoint(52.37, 4.90), PoiType.BUILDING, description = "weigh house in Amsterdam, Netherlands",
            facts = PlaceFacts(
                PlaceKind.HISTORIC_BUILDING, description = "weigh house in Amsterdam, Netherlands",
                since = HistoricDate(1488, Precision.YEAR), architects = listOf("Hendrick de Keyser"),
                styles = listOf("Gothic architecture"), heritage = "a Rijksmonument, a listed national monument",
                occupant = "restaurant In de Waag",
            ),
        )
        repeat(20) { seed ->
            val story = PlaceStoryWriter(Random(seed), currentYear = 2026).write(poi, maxSentences = 6)
            assertEquals("It is a weigh house in Amsterdam, Netherlands.", story.first())
            assertTrue(story.any { "1488" in it }, "$story")
            assertTrue(story.any { "Hendrick de Keyser" in it })
            assertTrue(story.any { "Gothic architecture" in it })
            assertTrue(story.any { "Rijksmonument" in it })
            assertTrue(story.none { "_" in it || "=" in it }, "$story")
            assertTrue(story.any { "more than 500 years ago" in it } || story.none { "years ago" in it })
        }
        assertEquals(2, PlaceStoryWriter(Random(1)).write(poi, maxSentences = 2).size)
        assertEquals(500, PlaceStoryWriter.roundedAge(538))
        assertEquals(150, PlaceStoryWriter.roundedAge(160))
        assertEquals("the municipal council", English.withThe("municipal council"))
    }

    @Test
    fun `Wikidata answers are cached and batched`() = runTest {
        val requests = mutableListOf<String>()
        val client = WikidataClient({ url -> requests += url; entitiesJson })
        client.entities(listOf("Q2601", "Q1404"))
        client.entities(listOf("Q1404"))
        assertEquals(1, requests.size)
        assertTrue("languagefallback=1" in requests[0] && "sitefilter=enwiki" in requests[0])

        val many = WikidataClient({ url -> requests += url; """{"entities":{}}""" })
        requests.clear()
        many.entities((1..120).map { "Q$it" })
        assertEquals(3, requests.size)
        // Unknown items are remembered too.
        many.entities(listOf("Q5"))
        assertEquals(3, requests.size)
    }

    @Test
    fun `the town is found through Nominatim, Wikidata and Wikipedia, once`() = runTest {
        val requests = mutableListOf<String>()
        val http = { url: String ->
            requests += url
            when {
                "nominatim" in url -> """{"name":"Utrecht","addresstype":"city","extratags":{"wikidata":"Q803","wikipedia":"nl:Utrecht (stad)"}}"""
                "wikidata" in url -> """{"entities":{"Q803":{"id":"Q803","labels":{"en":{"language":"en","value":"Utrecht"}},"sitelinks":{"enwiki":{"site":"enwiki","title":"Utrecht"}}}}}"""
                else -> """{"query":{"pages":[{"pageid":32143,"title":"Utrecht","fullurl":"https://en.wikipedia.org/wiki/Utrecht",
                    "description":"City in the Netherlands","extract":"Utrecht is the fourth-largest city of the Netherlands.",
                    "pageprops":{"wikibase_item":"Q803"}}]}}"""
            }
        }
        val lookup = TownLookup(NominatimClient(http, clock = { 0L }), WikidataClient(http), WikipediaClient(http))
        val town = assertNotNull(lookup.town(center))
        assertEquals("Utrecht", town.title)
        assertEquals(PoiType.CITY, town.type)
        assertEquals(center, town.point, "no coordinates in the answer: placed where we are")
        assertEquals("Q803", town.wikidataId)
        assertTrue(requests[0].contains("reverse?format=jsonv2&zoom=10&extratags=1"))
        assertTrue("props=labels%7Cdescriptions%7Csitelinks" in requests[1], requests[1])
        assertTrue("titles=Utrecht" in requests[2])

        lookup.town(center)
        assertEquals(4, requests.size, "only Nominatim is asked again")
    }

    @Test
    fun `reverse geocoding ignores provinces and failures`() {
        val nominatim = NominatimClient({ "" })
        assertNull(nominatim.parseReverse("""{"error":"Unable to geocode"}"""))
        assertNull(nominatim.parseReverse("""{"name":"Utrecht","addresstype":"state","extratags":{"wikidata":"Q776"}}"""))
        assertEquals("Q9920", nominatim.parseReverse("""{"name":"Haarlem","addresstype":"city","extratags":{"wikidata":"Q9920"}}""")?.wikidata)
    }
}
