package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.motion.MotionTracker
import io.github.atrejoe.travelguide.core.motion.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MotionTrackerTest {
    private val start = GeoPoint(52.0, 5.0)

    /** Simulates travelling east at [speedMps] with one fix per second. */
    private fun travel(tracker: MotionTracker, speedMps: Double, seconds: Int, reportSpeed: Boolean = true, t0: Long = 0) {
        for (s in 0..seconds) {
            val p = GeoMath.destination(start, 90.0, speedMps * s)
            tracker.onFix(LocationFix(p, t0 + s * 1000L, speedMps.takeIf { reportSpeed }))
        }
    }

    @Test
    fun `classifies cycling and heading from displacement`() {
        val tracker = MotionTracker()
        travel(tracker, speedMps = 5.0, seconds = 30, reportSpeed = false)
        val state = assertNotNull(tracker.state)
        assertEquals(TravelMode.CYCLING, state.mode)
        assertEquals(90.0, state.headingDeg!!, 2.0)
        assertEquals(5.0, state.speedMps, 0.3)
    }

    @Test
    fun `mode change needs to persist before it is adopted`() {
        val tracker = MotionTracker(modeHoldMillis = 20_000)
        travel(tracker, speedMps = 30.0, seconds = 40)
        assertEquals(TravelMode.DRIVING, tracker.state!!.mode)
        // A short burst of high speed (GPS glitch) does not switch to train.
        for (s in 1..5) {
            tracker.onFix(LocationFix(GeoMath.destination(start, 90.0, 1_200.0 + s * 60.0), 40_000L + s * 1000, 60.0))
        }
        assertEquals(TravelMode.DRIVING, tracker.state!!.mode)
    }

    @Test
    fun `manual override wins`() {
        val tracker = MotionTracker()
        tracker.modeOverride = TravelMode.TRAIN
        travel(tracker, speedMps = 25.0, seconds = 10)
        assertEquals(TravelMode.TRAIN, tracker.state!!.mode)
    }

    @Test
    fun `inaccurate fixes are ignored`() {
        val tracker = MotionTracker()
        tracker.onFix(LocationFix(start, 0, accuracyM = 500.0))
        assertEquals(null, tracker.state)
    }
}
