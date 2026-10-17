package io.github.atrejoe.travelguide.core.rail

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.LocationFix
import kotlin.math.abs

/**
 * Recognises train travel from where you move rather than how fast: car and train speeds
 * overlap, but a train stays on its track.
 *
 * Over the last [windowMillis] most fixes must lie on a railway, clearly closer to it than to any
 * major road, while moving along the track. Once on the train it stays that way during station
 * stops, until most recent fixes are off the track.
 */
class RailDetector(
    private val windowMillis: Long = 90_000,
    private val minSpanMillis: Long = 45_000,
    private val minSamples: Int = 6,
) {
    private data class Sample(val timeMillis: Long, val onTrack: Boolean, val moving: Boolean)

    private val samples = ArrayDeque<Sample>()

    var onRail: Boolean = false
        private set

    fun update(fix: LocationFix, speedMps: Double, headingDeg: Double?, network: RailNetwork?): Boolean {
        if (network == null) {
            samples.clear()
            onRail = false
            return false
        }
        val match = network.nearestRail(fix.point)
        val tolerance = TRACK_TOLERANCE_M + (fix.accuracyM ?: 10.0).coerceAtMost(25.0)
        val close = match != null && match.distanceM <= tolerance
        val roadDistance = network.distanceToRoad(fix.point)
        val railIsNearest = match != null && (roadDistance == null || match.distanceM + ROAD_MARGIN_M < roadDistance)
        val aligned = headingDeg == null || speedMps < 3 || match == null || alongTrack(headingDeg, match.bearingDeg)
        samples.addLast(Sample(fix.timeMillis, close && railIsNearest && aligned, speedMps >= 5.0))
        while (samples.isNotEmpty() && fix.timeMillis - samples.first().timeMillis > windowMillis) samples.removeFirst()

        val span = samples.last().timeMillis - samples.first().timeMillis
        val share = samples.count { it.onTrack }.toDouble() / samples.size
        onRail = if (onRail) {
            share >= EXIT_SHARE
        } else {
            samples.size >= minSamples && span >= minSpanMillis && share >= ENTER_SHARE &&
                samples.count { it.moving } >= samples.size / 2
        }
        return onRail
    }

    fun reset() {
        samples.clear()
        onRail = false
    }

    private fun alongTrack(heading: Double, trackBearing: Double): Boolean {
        val diff = abs(GeoMath.angleDiff(heading, trackBearing))
        return diff <= ALIGN_DEG || diff >= 180 - ALIGN_DEG
    }

    private companion object {
        const val TRACK_TOLERANCE_M = 20.0
        const val ROAD_MARGIN_M = 15.0
        const val ALIGN_DEG = 30.0
        const val ENTER_SHARE = 0.8
        const val EXIT_SHARE = 0.4
    }
}
