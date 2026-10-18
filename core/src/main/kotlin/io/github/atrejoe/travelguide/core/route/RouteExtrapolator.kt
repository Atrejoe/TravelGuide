package io.github.atrejoe.travelguide.core.route

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.Maneuver
import io.github.atrejoe.travelguide.core.rail.RailGraph
import io.github.atrejoe.travelguide.core.road.RoadGraph
import io.github.atrejoe.travelguide.core.road.RoadPath
import kotlin.math.hypot

/** What a navigation app is telling us right now. */
data class LiveNavigation(
    /** The next maneuver, its distance counted from [origin]. */
    val maneuver: Maneuver,
    /** Where we were when the instruction came in; null when not known yet. */
    val origin: GeoPoint?,
    /** A route towards the place the instruction's signs point to, when one was computed. */
    val towardRoute: List<GeoPoint>? = null,
) {
    /** The maneuver with its distance counted from [here]. */
    fun maneuverFrom(here: GeoPoint): Maneuver {
        val d = maneuver.distanceM ?: return maneuver
        val travelled = origin?.let { GeoMath.distance(it, here) } ?: 0.0
        return maneuver.copy(distanceM = (d - travelled).coerceAtLeast(0.0))
    }
}

/**
 * Predicts the path ahead, from the best information available, in this order: a planned route
 * (shared or imported), the live instructions of a navigation app, the railway (by train), the
 * road we're on (driving or cycling), or else a straight line along the current heading.
 */
class RouteExtrapolator {
    /** Planned route, e.g. received from a navigation app. Null when not navigating. */
    var plannedRoute: List<GeoPoint>? = null

    /** Railway lines nearby; when travelling by train the forecast follows the track. */
    var railGraph: RailGraph? = null

    /** Roads nearby, followed when driving or cycling and to apply navigation instructions. */
    var roadGraph: RoadGraph? = null

    /** The instruction of a navigation app; null when not navigating. */
    var navigation: LiveNavigation? = null

    /** Last direction of travel, so a train waiting at a station still knows where it's heading. */
    private var lastHeading: Double? = null

    fun predict(motion: MotionState, lookaheadSeconds: Int, minDistanceM: Double = 300.0): PredictedPath {
        val here = motion.fix.point
        val distance = maxOf(motion.speedMps * lookaheadSeconds, minDistanceM)
        plannedRoute?.takeIf { it.size >= 2 }?.let { route ->
            followRoute(here, route, distance)?.let { return it }
        }
        motion.headingDeg?.let { lastHeading = it }
        val onRoad = motion.mode != TravelMode.TRAIN && motion.mode != TravelMode.FLYING
        val bicycle = motion.mode == TravelMode.CYCLING
        val nav = navigation
        if (nav != null && onRoad) {
            motion.headingDeg?.let { heading -> navigated(here, heading, distance, nav, bicycle)?.let { return it } }
        }
        if (motion.mode == TravelMode.TRAIN) {
            val heading = motion.headingDeg ?: lastHeading
            val track = heading?.let { railGraph?.follow(here, it, distance) }
            if (track != null) return PredictedPath(track, PathSource.RAILWAY)
        }
        val heading = motion.headingDeg ?: return PredictedPath(listOf(here), PathSource.NONE)
        if (motion.mode == TravelMode.DRIVING || motion.mode == TravelMode.CYCLING) {
            val road = roadGraph?.follow(here, heading, distance, bicycle = bicycle)
            if (road != null && road.lengthM >= MIN_ROAD_PATH_M) return PredictedPath(road.points, PathSource.ROAD)
        }
        return PredictedPath(listOf(here, GeoMath.destination(here, heading, distance)), PathSource.DEAD_RECKONING)
    }

    /**
     * The path ahead from a navigation instruction: along the road, through the next maneuver. A
     * route towards the signposted place is used instead while we're on it and it agrees with
     * where the maneuver is. Null when neither fits.
     */
    private fun navigated(here: GeoPoint, heading: Double, distanceM: Double, nav: LiveNavigation, bicycle: Boolean): PredictedPath? {
        val road = roadGraph?.follow(here, heading, distanceM, nav.maneuverFrom(here), bicycle)
        nav.towardRoute?.takeIf { it.size >= 2 }?.let { route ->
            if (road == null || agrees(route, road)) {
                followRoute(here, route, distanceM, maxOffRouteM = TOWARD_AGREEMENT_M, source = PathSource.NAVIGATION)?.let { return it }
            }
        }
        road ?: return null
        return when {
            road.guided && road.lengthM >= MIN_NAVIGATED_PATH_M -> PredictedPath(road.points, PathSource.NAVIGATION)
            road.lengthM >= MIN_ROAD_PATH_M -> PredictedPath(road.points, PathSource.ROAD)
            else -> null
        }
    }

    /**
     * True when [route] passes the maneuver of [road] and leaves it the same way, i.e. it goes
     * through the junction and also through a point a little beyond it on the road path.
     */
    private fun agrees(route: List<GeoPoint>, road: RoadPath): Boolean {
        val maneuver = road.maneuverPoint ?: return true
        val routePath = PredictedPath(route)
        if (routePath.locate(maneuver).crossM > TOWARD_AGREEMENT_M) return false
        val along = PredictedPath(road.points).locate(maneuver).alongM
        if (road.lengthM < along + AGREEMENT_PROBE_M) return true
        val beyond = RoadGraph.pointAt(road.points, along + AGREEMENT_PROBE_M)
        return routePath.locate(beyond).crossM <= TOWARD_AGREEMENT_M
    }

    /**
     * The part of [route] from the point on it nearest to [here] onwards, cut at [distanceM]. The
     * nearest point is searched on the segments, not only the vertices, so simplified routes with
     * long straight stretches are followed too.
     */
    internal fun followRoute(
        here: GeoPoint,
        route: List<GeoPoint>,
        distanceM: Double,
        maxOffRouteM: Double = MAX_OFF_ROUTE_M,
        source: PathSource = PathSource.PLANNED_ROUTE,
    ): PredictedPath? {
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
        // Off-route (by a kilometre for a planned route): the route is stale, fall back to guessing.
        if (bestDistance > maxOffRouteM) return null

        val result = mutableListOf(here)
        var remaining = distanceM
        var previous = here
        for (p in route.drop(bestSegment + 1)) {
            val leg = GeoMath.distance(previous, p)
            if (leg >= remaining) {
                result += GeoMath.destination(previous, GeoMath.bearing(previous, p), remaining)
                return PredictedPath(result, source)
            }
            result += p
            remaining -= leg
            previous = p
        }
        return PredictedPath(result, source)
    }

    private companion object {
        const val MAX_OFF_ROUTE_M = 1_000.0

        /** A route towards the signposted place must pass this close to us and to the maneuver. */
        const val TOWARD_AGREEMENT_M = 40.0
        const val AGREEMENT_PROBE_M = 80.0

        /** Shorter road paths (e.g. a T-junction just ahead) say too little to draw. */
        const val MIN_ROAD_PATH_M = 50.0
        const val MIN_NAVIGATED_PATH_M = 20.0
    }
}
