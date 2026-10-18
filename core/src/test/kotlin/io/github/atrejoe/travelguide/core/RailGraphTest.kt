package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.rail.OsmWay
import io.github.atrejoe.travelguide.core.rail.RailGraph
import io.github.atrejoe.travelguide.core.rail.RailNetwork
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.route.RouteExtrapolator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailGraphTest {
    private val a = GeoPoint(52.0, 5.0)
    // North for 2 km, then a gentle bend to the north-east for 3 km.
    private val b = GeoMath.destination(a, 0.0, 2_000.0)
    private val c = GeoMath.destination(b, 30.0, 3_000.0)
    // At b a branch line turns sharply east; trains go straight on.
    private val branchEnd = GeoMath.destination(b, 100.0, 2_000.0)

    private val main = OsmWay(1, listOf(1, 2), listOf(a, b))
    private val bend = OsmWay(2, listOf(2, 3), listOf(b, c))
    private val branch = OsmWay(3, listOf(2, 4), listOf(b, branchEnd))
    private val network = RailNetwork(a, 10_000.0, listOf(main, bend, branch), emptyList(), 0)
    private val graph = RailGraph(network)

    @Test
    fun `follows the track through a bend and ignores the sharp branch`() {
        val start = GeoMath.destination(a, 0.0, 500.0)
        val path = assertNotNull(graph.follow(GeoMath.destination(start, 90.0, 15.0), headingDeg = 2.0, distanceM = 3_000.0))
        // Snapped onto the track.
        assertEquals(0.0, GeoMath.distance(path.first(), start), 2.0)
        // 1.5 km to the bend, then 1.5 km along the north-east section.
        val end = path.last()
        assertEquals(1_500.0, GeoMath.distance(b, end), 15.0)
        assertEquals(30.0, GeoMath.bearing(b, end), 1.0)
        val length = path.zipWithNext().sumOf { (p, q) -> GeoMath.distance(p, q) }
        assertEquals(3_000.0, length, 15.0)
    }

    @Test
    fun `travelling the other way follows the track back`() {
        val start = GeoMath.destination(b, 30.0, 1_000.0)
        val path = assertNotNull(graph.follow(start, headingDeg = 210.0, distanceM = 2_000.0))
        // Through b and then south along the main line.
        assertTrue(path.last().lat < b.lat)
        assertEquals(180.0, GeoMath.bearing(b, path.last()), 1.0)
    }

    @Test
    fun `no forecast when the track is too far away`() {
        assertNull(graph.follow(GeoMath.destination(a, 90.0, 500.0), 0.0, 1_000.0))
    }

    @Test
    fun `the extrapolator follows the railway by train and keeps going during a stop`() {
        val extrapolator = RouteExtrapolator().apply { railGraph = graph }
        val here = GeoMath.destination(a, 0.0, 1_000.0)
        val moving = MotionState(LocationFix(here, 0), speedMps = 30.0, headingDeg = 0.0, mode = TravelMode.TRAIN)
        val path = extrapolator.predict(moving, lookaheadSeconds = 100)
        assertEquals(PathSource.RAILWAY, path.source)
        assertEquals(30.0, GeoMath.bearing(b, path.end), 1.0)

        val stopped = moving.copy(speedMps = 0.0, headingDeg = null)
        assertEquals(PathSource.RAILWAY, extrapolator.predict(stopped, lookaheadSeconds = 100).source)
    }

    @Test
    fun `a planned route still wins, and cars ignore the railway`() {
        val extrapolator = RouteExtrapolator().apply { railGraph = graph }
        val here = GeoMath.destination(a, 0.0, 1_000.0)
        val car = MotionState(LocationFix(here, 0), speedMps = 30.0, headingDeg = 0.0, mode = TravelMode.DRIVING)
        assertEquals(PathSource.DEAD_RECKONING, extrapolator.predict(car, 100).source)

        extrapolator.plannedRoute = listOf(here, GeoMath.destination(here, 90.0, 5_000.0))
        val train = car.copy(mode = TravelMode.TRAIN)
        assertEquals(PathSource.PLANNED_ROUTE, extrapolator.predict(train, 100).source)
    }
}
