package io.github.atrejoe.travelguide.core.route

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.MotionState

/**
 * Predicts the path ahead.
 *
 * With a planned route (from a navigation app or an imported track) we follow it; otherwise we
 * dead-reckon in a straight line along the current heading.
 */
class RouteExtrapolator {
    /** Planned route, e.g. received from a navigation app. Null when not navigating. */
    var plannedRoute: List<GeoPoint>? = null

    fun predict(motion: MotionState, lookaheadSeconds: Int, minDistanceM: Double = 300.0): PredictedPath {
        val here = motion.fix.point
        val distance = maxOf(motion.speedMps * lookaheadSeconds, minDistanceM)
        plannedRoute?.takeIf { it.size >= 2 }?.let { route ->
            followRoute(here, route, distance)?.let { return it }
        }
        val heading = motion.headingDeg ?: return PredictedPath(listOf(here))
        return PredictedPath(listOf(here, GeoMath.destination(here, heading, distance)))
    }

    /** The part of [route] from the point nearest to [here] onwards, cut at [distanceM]. */
    internal fun followRoute(here: GeoPoint, route: List<GeoPoint>, distanceM: Double): PredictedPath? {
        val nearestIndex = route.indices.minBy { GeoMath.distance(here, route[it]) }
        // Off-route by more than a kilometre: the route is stale, fall back to dead reckoning.
        if (GeoMath.distance(here, route[nearestIndex]) > 1_000) return null

        val result = mutableListOf(here)
        var remaining = distanceM
        var previous = here
        for (p in route.drop(nearestIndex + 1)) {
            val leg = GeoMath.distance(previous, p)
            if (leg >= remaining) {
                result += GeoMath.destination(previous, GeoMath.bearing(previous, p), remaining)
                return PredictedPath(result)
            }
            result += p
            remaining -= leg
            previous = p
        }
        return PredictedPath(result)
    }
}
