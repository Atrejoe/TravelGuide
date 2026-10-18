package io.github.atrejoe.travelguide.core.rail

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import kotlin.math.abs

/**
 * The railway lines of a [RailNetwork] as a graph of OpenStreetMap nodes, so the route ahead of
 * a train can be followed along its track, through bends and junctions.
 */
class RailGraph(network: RailNetwork) {
    private val coordinates = HashMap<Long, GeoPoint>()
    private val neighbours = HashMap<Long, MutableSet<Long>>()
    private val network = network

    init {
        var synthetic = -1L
        for (way in network.rails) {
            // Overpass returns node ids alongside the geometry; if they don't line up, the way is
            // still usable on its own, just not connected to others.
            val ids = if (way.nodeIds.size == way.points.size) way.nodeIds else way.points.map { synthetic-- }
            ids.forEachIndexed { i, id ->
                coordinates[id] = way.points[i]
                if (i > 0) {
                    neighbours.getOrPut(id) { mutableSetOf() } += ids[i - 1]
                    neighbours.getOrPut(ids[i - 1]) { mutableSetOf() } += id
                }
            }
        }
    }

    /**
     * The track ahead of [position] when travelling in direction [headingDeg], [distanceM] long,
     * starting at the point on the track nearest to [position]. Null when no track is within
     * [maxSnapM]. At junctions the straightest continuation is taken; the walk stops at a dead
     * end or a turn sharper than a train can take.
     */
    fun follow(position: GeoPoint, headingDeg: Double, distanceM: Double, maxSnapM: Double = 60.0): List<GeoPoint>? {
        val match = network.nearestRail(position) ?: return null
        if (match.distanceM > maxSnapM) return null
        val way = match.way
        val ids = if (way.nodeIds.size == way.points.size) way.nodeIds else return followWayOnly(match, position, headingDeg, distanceM)
        val a = ids[match.segmentIndex]
        val b = ids[match.segmentIndex + 1]
        val forward = abs(GeoMath.angleDiff(headingDeg, match.bearingDeg)) <= 90
        var previous = if (forward) a else b
        var current = if (forward) b else a

        val snapped = snap(position, coordinates.getValue(a), coordinates.getValue(b))
        val path = mutableListOf(snapped)
        var remaining = distanceM
        var from = snapped
        var direction = GeoMath.bearing(coordinates.getValue(previous), coordinates.getValue(current))
        val visited = HashSet<Long>()
        while (remaining > 0 && visited.add(current)) {
            val to = coordinates.getValue(current)
            val leg = GeoMath.distance(from, to)
            if (leg >= remaining) {
                path += GeoMath.destination(from, GeoMath.bearing(from, to), remaining)
                return path
            }
            path += to
            remaining -= leg
            from = to
            val next = neighbours[current].orEmpty()
                .filter { it != previous }
                .map { it to abs(GeoMath.angleDiff(direction, GeoMath.bearing(to, coordinates.getValue(it)))) }
                .filter { it.second <= MAX_TURN_DEG }
                .minByOrNull { it.second }
                ?.first ?: break
            direction = GeoMath.bearing(to, coordinates.getValue(next))
            previous = current
            current = next
        }
        return path.takeIf { it.size >= 2 }
    }

    /** Fallback for a way without usable node ids: walk along its own geometry only. */
    private fun followWayOnly(match: TrackMatch, position: GeoPoint, headingDeg: Double, distanceM: Double): List<GeoPoint>? {
        val pts = match.way.points
        val forward = abs(GeoMath.angleDiff(headingDeg, match.bearingDeg)) <= 90
        val ahead = if (forward) pts.drop(match.segmentIndex + 1) else pts.take(match.segmentIndex + 1).reversed()
        val path = mutableListOf(snap(position, pts[match.segmentIndex], pts[match.segmentIndex + 1]))
        var remaining = distanceM
        for (p in ahead) {
            val leg = GeoMath.distance(path.last(), p)
            if (leg >= remaining) {
                path += GeoMath.destination(path.last(), GeoMath.bearing(path.last(), p), remaining)
                break
            }
            path += p
            remaining -= leg
        }
        return path.takeIf { it.size >= 2 }
    }

    private fun snap(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
        val segment = GeoMath.distance(a, b)
        if (segment < 0.5) return a
        val along = GeoMath.distance(a, p) * kotlin.math.cos(Math.toRadians(GeoMath.angleDiff(GeoMath.bearing(a, b), GeoMath.bearing(a, p))))
        return GeoMath.destination(a, GeoMath.bearing(a, b), along.coerceIn(0.0, segment))
    }

    private companion object {
        /** Sharper than any mainline switch: beyond this we'd be reversing onto another track. */
        const val MAX_TURN_DEG = 45.0
    }
}
