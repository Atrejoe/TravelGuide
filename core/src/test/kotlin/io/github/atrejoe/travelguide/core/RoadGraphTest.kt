package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.nav.ManeuverParser
import io.github.atrejoe.travelguide.core.rail.OsmWay
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.road.RoadGraph
import io.github.atrejoe.travelguide.core.road.RoadNetwork
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Roads from `overpass-roads.json`: Hoofdstraat runs north; 300 m from the start Kerkstraat
 * branches off to the west and Molenweg to the east; at 600 m it becomes the N201
 * (Provincialeweg), with Dorpsweg to the east, and on the N201 a slip road to Utrecht bears right.
 */
class RoadGraphTest {
    private val client = OverpassClient({ "" }, clock = { 42 })
    private val center = GeoPoint(52.003, 5.0)
    private val network = client.parseRoads(javaClass.getResource("/overpass-roads.json")!!.readText(), center, 5_000, 1_000, false)
    private val graph = RoadGraph(network)

    private val start = GeoPoint(52.0003, 5.0)
    private val junction = GeoPoint(52.003, 5.0)

    private fun maneuver(text: String) = assertNotNull(ManeuverParser.parse(text))

    @Test
    fun `overpass roads keep names, numbers and directions`() {
        assertEquals(7, network.ways.size)
        val n201 = network.ways.first { it.id == 4L }
        assertEquals(mapOf("highway" to "primary", "name" to "Provincialeweg", "ref" to "N201"), n201.tags)
        assertFalse(network.ways.first { it.id == 1L }.tags.containsKey("surface"))
        assertEquals(42, network.fetchedAtMillis)
        assertTrue(network.covers(center))

        val minor = URLDecoder.decode(client.roadsUrl(center, 5_000, 1_000, cycleways = true), "UTF-8")
        assertTrue("residential" in minor && "around:1000," in minor && "cycleway" in minor)
        val major = URLDecoder.decode(client.roadsUrl(center, 5_000, 0, cycleways = false), "UTF-8")
        assertFalse("residential" in major || "cycleway" in major)
        assertTrue("motorway" in major && "around:5000," in major)
    }

    @Test
    fun `without instructions the road is followed, straight on where it changes name`() {
        val path = assertNotNull(graph.follow(start, headingDeg = 2.0, distanceM = 1_000.0))
        assertFalse(path.guided)
        assertEquals(1_000.0, path.lengthM, 5.0)
        // Straight on through both junctions and onto the N201.
        assertEquals(5.0, path.points.last().lon, 1e-5)
        assertEquals(GeoMath.destination(start, 0.0, 1_000.0).lat, path.points.last().lat, 1e-4)
    }

    @Test
    fun `a named turn is taken at the junction the instruction points to`() {
        val path = assertNotNull(graph.follow(start, 0.0, 600.0, maneuver("300 m · Turn left onto Kerkstraat")))
        assertTrue(path.guided)
        assertEquals(0.0, GeoMath.distance(junction, assertNotNull(path.maneuverPoint)), 1.0)
        // 300 m to the junction, then 300 m west.
        val end = path.points.last()
        assertEquals(270.0, GeoMath.bearing(junction, end), 1.0)
        assertEquals(300.0, GeoMath.distance(junction, end), 5.0)
    }

    @Test
    fun `the name decides, in any language`() {
        // No direction word that we know, only the street name.
        val path = assertNotNull(graph.follow(start, 0.0, 600.0, maneuver("300 m · Prenez Molenweg")))
        assertEquals(90.0, GeoMath.bearing(junction, path.points.last()), 1.0)
    }

    @Test
    fun `without a name the direction decides`() {
        val dutch = assertNotNull(graph.follow(start, 0.0, 600.0, maneuver("Over 280 m · Sla rechtsaf")))
        assertEquals(90.0, GeoMath.bearing(junction, dutch.points.last()), 1.0)
        val german = assertNotNull(graph.follow(start, 0.0, 600.0, maneuver("Nach 320 m links abbiegen")))
        assertEquals(270.0, GeoMath.bearing(junction, german.points.last()), 1.0)
    }

    @Test
    fun `an exit is matched by the destinations on its signs`() {
        val onN201 = GeoPoint(52.0065, 5.0)
        val path = assertNotNull(graph.follow(onN201, 0.0, 600.0, maneuver("300 m · Neem de afrit richting Utrecht")))
        assertTrue(path.guided)
        assertEquals(0.0, GeoMath.distance(GeoPoint(52.009, 5.0), assertNotNull(path.maneuverPoint)), 1.0)
        assertEquals(GeoPoint(52.011, 5.002).lat, path.points.last().lat, 1e-5)
    }

    @Test
    fun `a turn that can't be placed ends the path where it is`() {
        // There is no left turn around 1 km ahead.
        val path = assertNotNull(graph.follow(start, 0.0, 2_000.0, maneuver("1 km · Turn left")))
        assertTrue(path.guided)
        assertEquals(1_000.0, path.lengthM, 5.0)
        assertEquals(1_000.0, GeoMath.distance(start, assertNotNull(path.maneuverPoint)), 5.0)
    }

    @Test
    fun `one-way streets and far-away roads are not matched`() {
        // 3 m from the one-way street going south, 17 m from Hoofdstraat: northbound, it's Hoofdstraat.
        val match = assertNotNull(graph.match(GeoPoint(52.002, 5.00025), 0.0))
        assertEquals(1L, match.way.id)
        assertEquals(6L, assertNotNull(graph.match(GeoPoint(52.002, 5.00025), 180.0)).way.id)
        assertNull(graph.follow(GeoPoint(52.002, 5.002), 0.0, 500.0))
        // Heading across the road: not on it.
        assertNull(graph.match(start, 90.0))
    }

    @Test
    fun `the right exit of a roundabout is taken`() {
        val c = GeoPoint(52.02, 5.0)
        val s = GeoMath.destination(c, 180.0, 20.0)
        val e = GeoMath.destination(c, 90.0, 20.0)
        val n = GeoMath.destination(c, 0.0, 20.0)
        val w = GeoMath.destination(c, 270.0, 20.0)
        val south = GeoMath.destination(s, 180.0, 500.0)
        val ways = listOf(
            OsmWay(1, listOf(1, 10), listOf(south, s), mapOf("highway" to "secondary")),
            // Counter-clockwise, as driven on the right.
            OsmWay(2, listOf(10, 11, 12, 13, 10), listOf(s, e, n, w, s), mapOf("highway" to "secondary", "junction" to "roundabout")),
            OsmWay(3, listOf(11, 31), listOf(e, GeoMath.destination(e, 90.0, 500.0)), mapOf("highway" to "secondary", "name" to "Oostweg")),
            OsmWay(4, listOf(12, 41), listOf(n, GeoMath.destination(n, 0.0, 500.0)), mapOf("highway" to "secondary", "name" to "Noordweg")),
            OsmWay(5, listOf(13, 51), listOf(w, GeoMath.destination(w, 270.0, 500.0)), mapOf("highway" to "secondary", "name" to "Westweg")),
        )
        val graph = RoadGraph(RoadNetwork(c, 5_000.0, 1_000.0, ways, 0))
        val here = GeoMath.destination(south, 0.0, 100.0)

        val second = assertNotNull(graph.follow(here, 0.0, 800.0, maneuver("400 m · At the roundabout, take the 2nd exit")))
        assertEquals(0.0, GeoMath.bearing(n, second.points.last()), 1.0)
        val named = assertNotNull(graph.follow(here, 0.0, 800.0, maneuver("Neem op de rotonde de afslag naar Westweg")))
        assertEquals(270.0, GeoMath.bearing(w, named.points.last()), 1.0)
        // Which exit is unknown: only the way up to the roundabout.
        val unknown = assertNotNull(graph.follow(here, 0.0, 800.0, maneuver("400 m · Kreisverkehr")))
        assertEquals(400.0, unknown.lengthM, 5.0)
        // Without instructions the roundabout isn't entered.
        assertEquals(400.0, assertNotNull(graph.follow(here, 0.0, 800.0)).lengthM, 5.0)
    }
}
