package io.github.atrejoe.travelguide.core.route

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import kotlin.math.hypot

/** Where a point lies relative to the predicted path. */
data class PathPosition(
    /** Distance along the path to the closest point, in metres. Negative when behind the start. */
    val alongM: Double,
    /** Distance from the path, in metres (always >= 0). */
    val crossM: Double,
)

/** Where a predicted path comes from, which decides how much the user should trust it. */
enum class PathSource {
    /** A route from navigation software: precise. */
    PLANNED_ROUTE,

    /** Live from a navigation app's instructions: along the road, through its next turn. */
    NAVIGATION,

    /** Following the railway we are on: a well-founded guess. */
    RAILWAY,

    /** Following the road we are on, without knowing where we will turn: a fair guess. */
    ROAD,

    /** Extended along the current heading: a guess. */
    DEAD_RECKONING,

    /** Not moving or no heading yet: only the current position is known. */
    NONE,
}

/**
 * The route we expect the user to follow over the next few minutes, as a polyline starting at
 * the current position.
 */
class PredictedPath(val points: List<GeoPoint>, val source: PathSource = PathSource.DEAD_RECKONING) {
    init {
        require(points.isNotEmpty()) { "A path needs at least one point" }
    }

    val start: GeoPoint get() = points.first()
    val end: GeoPoint get() = points.last()

    val lengthM: Double = points.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a, b) }

    fun locate(p: GeoPoint): PathPosition {
        val proj = LocalProjection(start)
        val (px, py) = proj.toXY(p)
        if (points.size == 1) return PathPosition(0.0, hypot(px, py))

        var best: PathPosition? = null
        var bestScore = Double.MAX_VALUE
        var travelled = 0.0
        val xy = points.map(proj::toXY)
        for (i in 0 until xy.size - 1) {
            val (ax, ay) = xy[i]
            val (bx, by) = xy[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val segLen = hypot(dx, dy)
            if (segLen < 1e-6) continue
            val rawT = ((px - ax) * dx + (py - ay) * dy) / (segLen * segLen)
            // Extend before the first and beyond the last segment, so "behind" and "further
            // ahead than we look" are measured along the route rather than as a sideways offset.
            val last = i == xy.size - 2
            val t = when {
                i == 0 && last -> rawT
                i == 0 -> rawT.coerceAtMost(1.0)
                last -> rawT.coerceAtLeast(0.0)
                else -> rawT.coerceIn(0.0, 1.0)
            }
            val cx = ax + t * dx
            val cy = ay + t * dy
            val cross = hypot(px - cx, py - cy)
            val along = travelled + t * segLen
            // Prefer the nearest segment; a point off either end must not win over a close one.
            val overshoot = when {
                along < 0 -> -along
                last && t > 1.0 -> (t - 1.0) * segLen
                else -> 0.0
            }
            val score = cross + overshoot
            if (score < bestScore) {
                bestScore = score
                best = PathPosition(along, cross)
            }
            travelled += segLen
        }
        return best ?: PathPosition(0.0, hypot(px, py))
    }
}
