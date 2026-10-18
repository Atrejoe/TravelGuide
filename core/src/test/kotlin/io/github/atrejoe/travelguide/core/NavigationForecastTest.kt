package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.ManeuverParser
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.road.RoadGraph
import io.github.atrejoe.travelguide.core.road.RoadNetwork
import io.github.atrejoe.travelguide.core.road.RoadSource
import io.github.atrejoe.travelguide.core.route.LiveNavigation
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.route.RouteExtrapolator
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The forecast from live navigation instructions and the road, on the roads of `overpass-roads.json`. */
class NavigationForecastTest {
    private val json = javaClass.getResource("/overpass-roads.json")!!.readText()
    private val overpass = OverpassClient({ "" }, clock = { 0 })
    private fun network(center: GeoPoint, minor: Int = 1_000): RoadNetwork = overpass.parseRoads(json, center, 5_000, minor, false)

    private val start = GeoPoint(52.0003, 5.0)
    private val junction = GeoPoint(52.003, 5.0)

    private fun car(at: GeoPoint = start, mode: TravelMode = TravelMode.DRIVING) =
        MotionState(LocationFix(at, 0), speedMps = 10.0, headingDeg = 0.0, mode = mode)

    private fun maneuver(text: String) = assertNotNull(ManeuverParser.parse(text))

    private val extrapolator = RouteExtrapolator().apply { roadGraph = RoadGraph(network(junction)) }

    @Test
    fun `driving follows the road, and a straight line where there is none`() {
        val path = extrapolator.predict(car(), lookaheadSeconds = 60)
        assertEquals(PathSource.ROAD, path.source)
        assertEquals(600.0, path.lengthM, 5.0)

        val offRoad = car(GeoPoint(52.002, 5.002))
        assertEquals(PathSource.DEAD_RECKONING, extrapolator.predict(offRoad, 60).source)
        // Walking isn't steered by roads without navigation.
        assertEquals(PathSource.DEAD_RECKONING, extrapolator.predict(car(mode = TravelMode.WALKING), 60).source)
    }

    @Test
    fun `navigation turns where the instruction says, counting down as we move`() {
        extrapolator.navigation = LiveNavigation(maneuver("300 m · Sla linksaf naar Kerkstraat"), origin = start)
        val path = extrapolator.predict(car(), lookaheadSeconds = 60)
        assertEquals(PathSource.NAVIGATION, path.source)
        assertEquals(270.0, GeoMath.bearing(junction, path.end), 1.0)

        // 200 m further the instruction (still saying 300 m) is counted from where it came in.
        val later = car(GeoMath.destination(start, 0.0, 200.0))
        val next = extrapolator.predict(later, lookaheadSeconds = 60)
        assertEquals(PathSource.NAVIGATION, next.source)
        assertEquals(270.0, GeoMath.bearing(junction, next.end), 1.0)

        // By train the instruction isn't applied to roads.
        assertEquals(PathSource.DEAD_RECKONING, extrapolator.predict(car(mode = TravelMode.TRAIN), 60).source)
        // A shared route still wins.
        extrapolator.plannedRoute = listOf(start, GeoMath.destination(start, 0.0, 5_000.0))
        assertEquals(PathSource.PLANNED_ROUTE, extrapolator.predict(car(), 60).source)
    }

    @Test
    fun `a route towards the signposted place is used only when it agrees with the turn`() {
        val west = GeoMath.destination(junction, 270.0, 5_000.0)
        val east = GeoMath.destination(junction, 90.0, 5_000.0)
        val instruction = maneuver("300 m · Turn left onto Kerkstraat toward Westdorp")
        assertEquals("Westdorp", instruction.toward)

        extrapolator.navigation = LiveNavigation(instruction, start, towardRoute = listOf(start, junction, west))
        val along = extrapolator.predict(car(), lookaheadSeconds = 300)
        assertEquals(PathSource.NAVIGATION, along.source)
        // Beyond the end of Kerkstraat (4.993) towards Westdorp.
        assertTrue(along.end.lon < 4.99, "${along.end}")
        assertEquals(3_000.0, along.lengthM, 5.0)

        extrapolator.navigation = LiveNavigation(instruction, start, towardRoute = listOf(start, junction, east))
        val road = extrapolator.predict(car(), lookaheadSeconds = 300)
        assertEquals(PathSource.NAVIGATION, road.source)
        assertTrue(road.end.lon > 4.99 && road.end.lon < 5.0, "${road.end}")
    }

    /** Drives north along Hoofdstraat at 10 m/s, one fix per second. */
    private fun GuideEngine.drive(seconds: Int, t0: Long = 0, from: Double = 0.0): Long {
        for (s in 0..seconds) {
            onLocation(LocationFix(GeoMath.destination(GeoPoint(52.0, 5.0), 0.0, from + s * 10.0), t0 + s * 1000L, 10.0, accuracyM = 5.0))
        }
        return t0 + seconds * 1000L
    }

    @Test
    fun `the engine fetches roads, follows navigation and forgets it when stale`() = runTest {
        val requests = mutableListOf<Int>()
        val roads = RoadSource { center, _, minor, _ -> requests += minor; network(center, minor) }
        var towardCalls = 0
        val engine = GuideEngine(
            { _, _ -> emptyList() },
            roads = roads,
            toward = { from, name, _ ->
                towardCalls++
                assertEquals("Westdorp", name)
                listOf(from, junction, GeoMath.destination(junction, 270.0, 5_000.0))
            },
        )
        val now = engine.drive(seconds = 5)
        engine.refreshIfNeeded(now)
        assertEquals(listOf(0), requests, "Only main roads without navigation")
        assertEquals(PathSource.ROAD, engine.predictedPath()!!.source)

        engine.onNavigation(maneuver("250 m · Turn left onto Kerkstraat toward Westdorp"), now)
        assertTrue(engine.navigating)
        assertEquals(PathSource.NAVIGATION, engine.predictedPath()!!.source)
        // Navigating at city speed: side streets are fetched, but not before the retry interval.
        engine.refreshIfNeeded(now + 1_000)
        assertEquals(listOf(0), requests)
        engine.refreshIfNeeded(now + 50_000)
        assertEquals(listOf(0, 1_000), requests)
        assertEquals(1, towardCalls)
        assertTrue(engine.predictedPath()!!.end.lon < 4.99)

        // Another update without a place keeps the route; no new lookup while we're on it.
        engine.onNavigation(maneuver("200 m · Turn left onto Kerkstraat"), now + 55_000)
        engine.refreshIfNeeded(now + 120_000)
        assertEquals(1, towardCalls)
        assertTrue(engine.predictedPath()!!.end.lon < 4.99)

        // No update for more than two minutes: navigation has ended or stalled.
        engine.refreshIfNeeded(now + 55_000 + 121_000)
        assertFalse(engine.navigating)
        assertEquals(PathSource.ROAD, engine.predictedPath()!!.source)

        engine.onNavigation(maneuver("250 m · Turn left"), now + 200_000)
        engine.onNavigation(null, now + 201_000)
        assertFalse(engine.navigating)
    }

    @Test
    fun `failing road fetches back off`() = runTest {
        val attempts = mutableListOf<Long>()
        var clock = 0L
        val engine = GuideEngine({ _, _ -> emptyList() }, roads = { _, _, _, _ -> attempts += clock; throw IOException("busy") })
        val start = engine.drive(seconds = 5)
        for (t in start..start + 400_000 step 5_000) {
            clock = t
            engine.refreshIfNeeded(t)
        }
        val gaps = attempts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(45_000L, 90_000L, 180_000L), gaps)
        assertEquals(PathSource.DEAD_RECKONING, engine.predictedPath()!!.source)
    }
}
