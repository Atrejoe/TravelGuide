package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.route.PredictedPath
import io.github.atrejoe.travelguide.core.route.RouteExtrapolator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTest {
    private val start = GeoPoint(52.0, 5.0)

    @Test
    fun `dead reckoning extends along heading for the lookahead time`() {
        val motion = MotionState(LocationFix(start, 0), speedMps = 20.0, headingDeg = 0.0, mode = TravelMode.DRIVING)
        val path = RouteExtrapolator().predict(motion, lookaheadSeconds = 300)
        assertEquals(6_000.0, path.lengthM, 10.0)
        assertTrue(path.end.lat > start.lat)
        assertEquals(PathSource.DEAD_RECKONING, path.source)
    }

    @Test
    fun `locate gives along and cross track distances`() {
        val path = PredictedPath(listOf(start, GeoMath.destination(start, 0.0, 5_000.0)))
        val east = GeoMath.destination(GeoMath.destination(start, 0.0, 2_000.0), 90.0, 300.0)
        val pos = path.locate(east)
        assertEquals(2_000.0, pos.alongM, 5.0)
        assertEquals(300.0, pos.crossM, 5.0)

        val beyond = GeoMath.destination(start, 0.0, 8_000.0)
        assertEquals(8_000.0, path.locate(beyond).alongM, 10.0)
        assertEquals(0.0, path.locate(beyond).crossM, 5.0)

        val behind = GeoMath.destination(start, 180.0, 1_000.0)
        assertEquals(-1_000.0, path.locate(behind).alongM, 5.0)
        assertEquals(0.0, path.locate(behind).crossM, 5.0)
    }

    @Test
    fun `planned route is followed around a corner`() {
        val corner = GeoMath.destination(start, 0.0, 1_000.0)
        val route = listOf(start, corner, GeoMath.destination(corner, 90.0, 5_000.0))
        val extrapolator = RouteExtrapolator().apply { plannedRoute = route }
        val motion = MotionState(LocationFix(start, 0), speedMps = 10.0, headingDeg = 0.0, mode = TravelMode.DRIVING)
        val path = extrapolator.predict(motion, lookaheadSeconds = 300)
        assertEquals(3_000.0, path.lengthM, 10.0)
        assertEquals(PathSource.PLANNED_ROUTE, path.source)
        // The end lies east of the corner, not straight north.
        assertTrue(GeoMath.bearing(corner, path.end) in 85.0..95.0)
    }

    @Test
    fun `stale planned route falls back to dead reckoning`() {
        val far = GeoMath.destination(start, 90.0, 20_000.0)
        val extrapolator = RouteExtrapolator().apply { plannedRoute = listOf(far, GeoMath.destination(far, 0.0, 1_000.0)) }
        val motion = MotionState(LocationFix(start, 0), speedMps = 10.0, headingDeg = 180.0, mode = TravelMode.DRIVING)
        val path = extrapolator.predict(motion, lookaheadSeconds = 100)
        assertTrue(path.end.lat < start.lat)
        assertEquals(PathSource.DEAD_RECKONING, path.source)
    }

    @Test
    fun `without a heading only the position is known`() {
        val motion = MotionState(LocationFix(start, 0), speedMps = 0.0, headingDeg = null, mode = TravelMode.STATIONARY)
        assertEquals(PathSource.NONE, RouteExtrapolator().predict(motion, 60).source)
    }
}
