package io.github.atrejoe.travelguide.core.rail

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import kotlin.math.atan2
import kotlin.math.hypot

/** An OpenStreetMap way: its node ids and their coordinates, in order. */
data class OsmWay(val id: Long, val nodeIds: List<Long>, val points: List<GeoPoint>)

/** Where the nearest track is and which way it runs. */
data class TrackMatch(val distanceM: Double, val bearingDeg: Double, val way: OsmWay, val segmentIndex: Int)

/**
 * Railway lines (and, for comparison, major roads) around [center], as fetched from
 * OpenStreetMap. Used to recognise train travel and to follow the track ahead.
 */
class RailNetwork(
    val center: GeoPoint,
    val radiusM: Double,
    val rails: List<OsmWay>,
    val roads: List<OsmWay>,
    val fetchedAtMillis: Long,
) {
    /** True when [p] lies well inside the area this network was fetched for. */
    fun covers(p: GeoPoint): Boolean = GeoMath.distance(center, p) < radiusM * 0.7

    fun nearestRail(p: GeoPoint): TrackMatch? = nearest(p, rails)

    fun distanceToRoad(p: GeoPoint): Double? = nearest(p, roads)?.distanceM

    private fun nearest(p: GeoPoint, ways: List<OsmWay>): TrackMatch? {
        val projection = LocalProjection(p)
        var best: TrackMatch? = null
        for (way in ways) {
            val xy = way.points.map(projection::toXY)
            for (i in 0 until xy.size - 1) {
                val (ax, ay) = xy[i]
                val (bx, by) = xy[i + 1]
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                // The query point is the projection's origin (0, 0).
                val t = if (len2 < 1e-9) 0.0 else ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (best == null || d < best.distanceM) {
                    val bearing = GeoMath.normalizeBearing(Math.toDegrees(atan2(dx, dy)))
                    best = TrackMatch(d, bearing, way, i)
                }
            }
        }
        return best
    }
}

/** Fetches the rail network around a point. */
fun interface RailSource {
    suspend fun around(center: GeoPoint, radiusM: Int): RailNetwork
}
