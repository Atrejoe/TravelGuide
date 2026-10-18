package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.rail.OsmWay
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.rail.RailNetwork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RailTest {
    private val start = GeoPoint(52.0, 5.0)
    private val trackEnd = GeoMath.destination(start, 0.0, 30_000.0)
    private val track = OsmWay(1, listOf(1, 2), listOf(start, trackEnd))
    // A primary road 60 m east of the track, running alongside it.
    private val road = OsmWay(2, listOf(3, 4), listOf(GeoMath.destination(start, 90.0, 60.0), GeoMath.destination(trackEnd, 90.0, 60.0)))
    private val network = RailNetwork(start, 40_000.0, listOf(track), listOf(road), 0)

    @Test
    fun `overpass response is split into rails and roads`() {
        val client = OverpassClient({ "" }, clock = { 42 })
        val parsed = client.parse(javaClass.getResource("/overpass-rail.json")!!.readText(), start, 3_000)
        assertEquals(listOf(101L), parsed.rails.map { it.id })
        assertEquals(listOf(202L), parsed.roads.map { it.id })
        assertEquals(42, parsed.fetchedAtMillis)
        val match = assertNotNull(parsed.nearestRail(GeoPoint(52.005, 5.0003)))
        assertEquals(20.0, match.distanceM, 3.0)
        assertTrue(match.bearingDeg < 1 || match.bearingDeg > 359)
        assertTrue("railway" in client.url(start, 3_000).let { java.net.URLDecoder.decode(it, "UTF-8") })
    }

    /** Moves north at [speedMps], [offsetM] east of the track, one fix per second. */
    private fun GuideEngine.travel(speedMps: Double, seconds: Int, offsetM: Double = 0.0, t0: Long = 0, from: Double = 0.0): Double {
        var along = from
        for (s in 0..seconds) {
            val p = GeoMath.destination(GeoMath.destination(start, 0.0, along), 90.0, offsetM)
            onLocation(LocationFix(p, t0 + s * 1000L, speedMps, accuracyM = 8.0))
            along += speedMps
        }
        return along
    }

    @Test
    fun `a regional train at 110 km per hour is detected as a train`() = runTest {
        val engine = GuideEngine({ _, _ -> emptyList() }, rails = { _, _ -> network })
        engine.travel(speedMps = 30.0, seconds = 5)
        engine.refreshIfNeeded(5_000)
        engine.travel(speedMps = 30.0, seconds = 120, t0 = 6_000, from = 180.0)
        assertTrue(engine.onRail)
        assertEquals(TravelMode.TRAIN, engine.motion.state!!.mode)
    }

    @Test
    fun `a car on a road next to the railway stays a car`() = runTest {
        val engine = GuideEngine({ _, _ -> emptyList() }, rails = { _, _ -> network })
        engine.travel(speedMps = 30.0, seconds = 5, offsetM = 60.0)
        engine.refreshIfNeeded(5_000)
        engine.travel(speedMps = 30.0, seconds = 120, offsetM = 60.0, t0 = 6_000, from = 180.0)
        assertFalse(engine.onRail)
        assertEquals(TravelMode.DRIVING, engine.motion.state!!.mode)
    }

    @Test
    fun `a station stop keeps the train mode`() = runTest {
        val engine = GuideEngine({ _, _ -> emptyList() }, rails = { _, _ -> network })
        engine.travel(speedMps = 30.0, seconds = 5)
        engine.refreshIfNeeded(5_000)
        val along = engine.travel(speedMps = 30.0, seconds = 120, t0 = 6_000, from = 180.0)
        // Two minutes standing at a platform, 8 m from the track.
        for (s in 1..120) {
            val p = GeoMath.destination(GeoMath.destination(start, 0.0, along), 90.0, 8.0)
            engine.onLocation(LocationFix(p, 126_000L + s * 1000L, 0.0, accuracyM = 8.0))
        }
        assertTrue(engine.onRail)
        assertEquals(TravelMode.TRAIN, engine.motion.state!!.mode)
    }

    @Test
    fun `without rail data speed decides as before`() = runTest {
        val engine = GuideEngine({ _, _ -> emptyList() })
        engine.travel(speedMps = 30.0, seconds = 60)
        engine.refreshIfNeeded(60_000)
        assertEquals(TravelMode.DRIVING, engine.motion.state!!.mode)
    }
}
