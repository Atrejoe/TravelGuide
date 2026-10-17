package io.github.atrejoe.travelguide.core.route

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.rail.RailGraph
import kotlin.math.hypot

/**
 * Predicts the path ahead.
 *
 * With a planned route (from a navigation app or an imported track) we follow it; otherwise we
 * dead-reckon in a straight line along the current heading.
 */
class RouteExtrapolator {
    /** Planned route, e.g. received from a navigation app. Null when not navigating. */
    var plannedRoute: List<GeoPoint>? = null

    /** Railway lines nearby; when travelling by train the forecast follows the track. */
    var railGraph: RailGraph? = null

    /** Last direction of travel, so a train waiting at a station still knows where it's heading. */
    private var lastHeading: Double? = null

    fun predict(motion: MotionState, lookaheadSeconds: Int, minDistanceM: Double = 300.0): PredictedPath {
        val here = motion.fix.point
        val distance = maxOf(motion.speedMps * lookaheadSeconds, minDistanceM)
        plannedRoute?.takeIf { it.size >= 2 }?.let { route ->
            followRoute(here, route, distance)?.let { return it }
        }
        motion.headingDeg?.let { lastHeading = it }
        if (motion.mode == TravelMode.TRAIN) {
            val heading = motion.headingDeg ?: lastHeading
            val track = heading?.let { railGraph?.follow(here, it, distance) }
            if (track != null) return PredictedPath(track, PathSource.RAILWAY)
        }
        val heading = motion.headingDeg ?: return PredictedPath(listOf(here), PathSource.NONE)
        return PredictedPath(listOf(here, GeoMath.destination(here, heading, distance)), PathSource.DEAD_RECKONING)
    }

    /**
     * The part of [route] from the point on it nearest to [here] onwards, cut at [distanceM]. The
     * nearest point is searched on the segments, not only the vertices, so simplified routes with
     * long straight stretches are followed too.
     */
    internal fun followRoute(here: GeoPoint, route: List<GeoPoint>, distanceM: Double): PredictedPath? {
        val proj = LocalProjection(here)
        val xy = route.map(proj::toXY)
        var bestSegment = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until xy.size - 1) {
            val (ax, ay) = xy[i]
            val (bx, by) = xy[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            // `here` is the origin of the projection.
            val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestDistance) {
                bestDistance = d
                bestSegment = i
            }
        }
        // Off-route by more than a kilometre: the route is stale, fall back to dead reckoning.
        if (bestDistance > 1_000) return null

        val result = mutableListOf(here)
        var remaining = distanceM
        var previous = here
        for (p in route.drop(bestSegment + 1)) {
            val leg = GeoMath.distance(previous, p)
            if (leg >= remaining) {
                result += GeoMath.destination(previous, GeoMath.bearing(previous, p), remaining)
                return PredictedPath(result, PathSource.PLANNED_ROUTE)
            }
            result += p
            remaining -= leg
            previous = p
        }
        return PredictedPath(result, PathSource.PLANNED_ROUTE)
    }
}
