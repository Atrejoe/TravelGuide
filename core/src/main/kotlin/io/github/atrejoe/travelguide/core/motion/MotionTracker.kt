package io.github.atrejoe.travelguide.core.motion

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.LocationFix

/** Smoothed view of how the user is currently moving. */
data class MotionState(
    val fix: LocationFix,
    val speedMps: Double,
    /** Direction of travel, or null when not moving enough to tell. */
    val headingDeg: Double?,
    val mode: TravelMode,
)

/**
 * Turns noisy location fixes into a stable speed, heading and [TravelMode].
 *
 * Speed is the median over a short window so a single GPS jump does not flip the mode, and a new
 * mode must persist for [modeHoldMillis] before it is adopted.
 */
class MotionTracker(
    private val windowMillis: Long = 30_000,
    private val modeHoldMillis: Long = 20_000,
    private val maxAccuracyM: Double = 100.0,
) {
    private val fixes = ArrayDeque<LocationFix>()
    private var mode: TravelMode = TravelMode.STATIONARY
    private var candidateMode: TravelMode? = null
    private var candidateSince: Long = 0
    private var lastHeading: Double? = null

    /** Manual override; null means automatic detection. */
    var modeOverride: TravelMode? = null

    var state: MotionState? = null
        private set

    fun onFix(fix: LocationFix): MotionState? {
        if (fix.accuracyM != null && fix.accuracyM > maxAccuracyM) return state
        if (fixes.isNotEmpty() && fix.timeMillis <= fixes.last().timeMillis) return state

        fixes.addLast(fix)
        while (fixes.size > 2 && fix.timeMillis - fixes.first().timeMillis > windowMillis) {
            fixes.removeFirst()
        }

        val speed = estimateSpeed()
        updateMode(TravelMode.fromSpeed(speed), fix.timeMillis)
        val heading = estimateHeading(speed)
        if (heading != null) lastHeading = heading
        // Keep the last known heading briefly when stopping (traffic lights, stations).
        val effectiveHeading = heading ?: lastHeading.takeIf { speed > 0.3 }

        return MotionState(fix, speed, effectiveHeading, modeOverride ?: mode).also { state = it }
    }

    private fun estimateSpeed(): Double {
        val reported = fixes.mapNotNull { it.speedMps }
        if (reported.size >= fixes.size / 2 && reported.isNotEmpty()) {
            return reported.sorted()[reported.size / 2]
        }
        if (fixes.size < 2) return 0.0
        val first = fixes.first()
        val last = fixes.last()
        val seconds = (last.timeMillis - first.timeMillis) / 1000.0
        if (seconds <= 0) return 0.0
        return GeoMath.distance(first.point, last.point) / seconds
    }

    private fun estimateHeading(speed: Double): Double? {
        if (speed < 0.7) return null
        val last = fixes.last()
        // Prefer displacement over the window: robust against compass-less GPS bearings jitter.
        val anchor = fixes.firstOrNull { GeoMath.distance(it.point, last.point) >= 25.0 }
        if (anchor != null && anchor !== last) {
            val moved = fixes.dropWhile { it !== anchor }
            val bearings = moved.zipWithNext().mapNotNull { (a, b) ->
                if (GeoMath.distance(a.point, b.point) < 3.0) null else GeoMath.bearing(a.point, b.point)
            }
            return GeoMath.meanBearing(bearings) ?: GeoMath.bearing(anchor.point, last.point)
        }
        return last.bearingDeg
    }

    private fun updateMode(observed: TravelMode, now: Long) {
        if (observed == mode) {
            candidateMode = null
            return
        }
        if (candidateMode != observed) {
            candidateMode = observed
            candidateSince = now
        }
        // The very first classification is adopted immediately.
        if (fixes.size <= 2 || now - candidateSince >= modeHoldMillis) {
            mode = observed
            candidateMode = null
        }
    }
}
