package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import kotlin.math.hypot

/**
 * Thins out dense tracks (a GPS recording has a point every second) so following the route stays
 * cheap, without changing its shape by more than a few metres.
 */
object RouteSimplifier {

    /**
     * Drops consecutive duplicates and points closer than [minSpacingM] to the previous kept point,
     * then applies Douglas–Peucker with [toleranceM]. The first and last points are always kept.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double = 10.0, minSpacingM: Double = 5.0): List<GeoPoint> {
        if (points.size <= 2) return points.distinct()
        val spaced = ArrayList<GeoPoint>(points.size)
        for (p in points) {
            val last = spaced.lastOrNull()
            if (last == null || GeoMath.distance(last, p) >= minSpacingM) spaced += p
        }
        if (spaced.last() != points.last()) {
            if (spaced.size > 1 && GeoMath.distance(spaced.last(), points.last()) < minSpacingM) spaced.removeAt(spaced.lastIndex)
            spaced += points.last()
        }
        if (spaced.size <= 2) return spaced
        return douglasPeucker(spaced, toleranceM)
    }

    /** Iterative, so very long tracks cannot overflow the stack. */
    private fun douglasPeucker(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last - first < 2) continue
            val proj = LocalProjection(points[first])
            val b = proj.toXY(points[last])
            var maxDistance = -1.0
            var maxIndex = -1
            for (i in first + 1 until last) {
                val d = segmentDistance(proj.toXY(points[i]), b)
                if (d > maxDistance) {
                    maxDistance = d
                    maxIndex = i
                }
            }
            if (maxDistance > toleranceM) {
                keep[maxIndex] = true
                stack.addLast(first to maxIndex)
                stack.addLast(maxIndex to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance from [p] to the segment from the origin to [b], in projected metres. */
    private fun segmentDistance(p: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (px, py) = p
        val (bx, by) = b
        val len2 = bx * bx + by * by
        if (len2 < 1e-9) return hypot(px, py)
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }
}
