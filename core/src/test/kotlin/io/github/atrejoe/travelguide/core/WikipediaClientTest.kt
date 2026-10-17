package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.wiki.WikipediaClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WikipediaClientTest {
    private val fixture = javaClass.getResource("/geosearch-utrecht.json")!!.readText()

    @Test
    fun `parses geosearch results with type, population and dim`() = runTest {
        var requested = ""
        val client = WikipediaClient({ url -> requested = url; fixture })
        val pois = client.nearby(GeoPoint(52.09, 5.12), 2_500)

        assertTrue(requested.startsWith("https://en.wikipedia.org/w/api.php?action=query"))
        assertTrue("ggscoord=52.090000%7C5.120000" in requested)
        assertTrue("ggsradius=2500" in requested)

        assertEquals(2, pois.size)
        val city = pois.first { it.title == "Utrecht" }
        assertEquals(PoiType.CITY, city.type)
        assertEquals(361_924L, city.population)
        assertEquals(10_000.0, city.dimM)
        val tower = pois.first { it.pageId == 1004556L }
        assertEquals(PoiType.LANDMARK, tower.type)
        assertEquals(100.0, tower.dimM)
        assertEquals("https://en.wikipedia.org/wiki/Dom_Tower_of_Utrecht", tower.url)
    }

    @Test
    fun `radius is clamped to the API maximum`() {
        val url = WikipediaClient({ "" }, lang = "nl").nearbyUrl(GeoPoint(52.0, 5.0), 50_000)
        assertTrue(url.startsWith("https://nl.wikipedia.org/"))
        assertTrue("ggsradius=10000" in url)
    }
}
