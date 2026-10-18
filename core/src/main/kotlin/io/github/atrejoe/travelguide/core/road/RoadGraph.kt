package io.github.atrejoe.travelguide.core.road

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocalProjection
import io.github.atrejoe.travelguide.core.nav.Maneuver
import io.github.atrejoe.travelguide.core.nav.TurnDirection
import io.github.atrejoe.travelguide.core.rail.OsmWay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** The road we are on: the way, the segment (oriented in the direction of travel) and where on it. */
data class RoadMatch(
    val way: OsmWay,
    /** The node behind us. */
    val fromNode: Long,
    /** The node ahead of us. */
    val toNode: Long,
    val snapped: GeoPoint,
    val distanceM: Double,
    /** Angle between our heading and the road, degrees. */
    val angleDeg: Double,
)

/** The road ahead, as followed by [RoadGraph.follow]. */
data class RoadPath(
    val points: List<GeoPoint>,
    /** True when a navigation instruction shaped the path (and not only the road layout). */
    val guided: Boolean,
    /** The junction where the instruction's maneuver was placed, when it was. */
    val maneuverPoint: GeoPoint? = null,
) {
    val lengthM: Double get() = points.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a, b) }
}

/**
 * The roads of a [RoadNetwork] as a graph of OpenStreetMap nodes, so the route ahead can be
 * followed along the road we're on, and through the next turn of a navigation instruction.
 */
class RoadGraph(val network: RoadNetwork) {
    private class Edge(val to: Long, val way: OsmWay, val forward: Boolean)

    private val coordinates = HashMap<Long, GeoPoint>()
    private val edges = HashMap<Long, MutableList<Edge>>()
    /** Ways whose node ids line up with their geometry; others can't be connected. */
    private val ways = network.ways.filter { it.nodeIds.size == it.points.size && it.points.size >= 2 }

    init {
        for (way in ways) {
            way.nodeIds.forEachIndexed { i, id ->
                coordinates[id] = way.points[i]
                if (i > 0) {
                    val previous = way.nodeIds[i - 1]
                    edges.getOrPut(previous) { mutableListOf() } += Edge(id, way, forward = true)
                    edges.getOrPut(id) { mutableListOf() } += Edge(previous, way, forward = false)
                }
            }
        }
    }

    /**
     * The road we're most likely on: within [maxSnapM] of [position], running within
     * [maxAngleDeg] of [headingDeg], and open in that direction. Null when nothing fits well.
     */
    fun match(
        position: GeoPoint,
        headingDeg: Double,
        bicycle: Boolean = false,
        maxSnapM: Double = MAX_SNAP_M,
        maxAngleDeg: Double = MAX_MATCH_ANGLE_DEG,
    ): RoadMatch? {
        val projection = LocalProjection(position)
        var best: RoadMatch? = null
        var bestScore = Double.MAX_VALUE
        for (way in ways) {
            val oneway = way.oneway(bicycle)
            val xy = way.points.map(projection::toXY)
            for (i in 0 until xy.size - 1) {
                val (ax, ay) = xy[i]
                val (bx, by) = xy[i + 1]
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-6) continue
                // The position is the projection's origin (0, 0).
                val t = ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d > maxSnapM) continue
                val bearing = GeoMath.normalizeBearing(Math.toDegrees(atan2(dx, dy)))
                val diff = abs(GeoMath.angleDiff(headingDeg, bearing))
                val forward = diff <= 90
                if ((forward && oneway == -1) || (!forward && oneway == 1)) continue
                val angle = if (forward) diff else 180 - diff
                if (angle > maxAngleDeg) continue
                val score = d + angle * 0.5
                if (score < bestScore) {
                    bestScore = score
                    val a = way.points[i]
                    val b = way.points[i + 1]
                    val snapped = GeoMath.destination(a, GeoMath.bearing(a, b), GeoMath.distance(a, b) * t)
                    best = RoadMatch(
                        way = way,
                        fromNode = if (forward) way.nodeIds[i] else way.nodeIds[i + 1],
                        toNode = if (forward) way.nodeIds[i + 1] else way.nodeIds[i],
                        snapped = snapped,
                        distanceM = d,
                        angleDeg = angle,
                    )
                }
            }
        }
        return best
    }

    /**
     * The road ahead of [position], [distanceM] long.
     *
     * Without a [maneuver], the road we're on is followed: along the same way, else a way with the
     * same name or number, else straight on when that is clearly the way the road goes. The walk
     * stops where it can't tell which way the road continues.
     *
     * With a [maneuver] (its distance counted from [position]), the road is followed up to the
     * maneuver, the branch the instruction names (or else the one in its direction, or the right
     * exit of a roundabout) is taken there, and the road is followed beyond. When the maneuver
     * can't be placed on a junction, the path ends where the maneuver is.
     *
     * Null when we don't seem to be on a known road.
     */
    fun follow(
        position: GeoPoint,
        headingDeg: Double,
        distanceM: Double,
        maneuver: Maneuver? = null,
        bicycle: Boolean = false,
    ): RoadPath? {
        val match = match(position, headingDeg, bicycle) ?: return null
        if (maneuver == null || !maneuver.isUseful) {
            val trace = walk(match.snapped, match.fromNode, match.toNode, match.way, distanceM, bicycle) { continuation(it, STRAIGHT_ON_DEG) }
            return RoadPath(trace.points, guided = false)
        }
        val d = maneuver.distanceM
        val tolerance = d?.let { maxOf(MIN_TOLERANCE_M, it * 0.2) } ?: 0.0
        val windowStart = d?.minus(tolerance) ?: 0.0
        val windowEnd = d?.plus(tolerance) ?: UNKNOWN_DISTANCE_WINDOW_M
        // Navigation tells us there is no turn before the maneuver, so be lenient about bends.
        val approach = walk(match.snapped, match.fromNode, match.toNode, match.way, maxOf(distanceM, windowEnd), bicycle) {
            continuation(it, NAVIGATED_STRAIGHT_ON_DEG)
        }

        var best: Pair<Junction, Edge>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (junction in approach.junctions) {
            if (junction.along < windowStart || junction.along > windowEnd) continue
            for (option in junction.options) {
                val score = score(junction, option, maneuver, d) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    best = junction to option
                }
            }
        }

        if (best == null) {
            val turn = maneuver.turn
            return when {
                // "Continue on…": the road ahead is confirmed.
                turn == TurnDirection.STRAIGHT -> RoadPath(cut(approach.points, distanceM), guided = true)
                // A turn somewhere we can't place: we only know the road up to it.
                d != null && turn != null -> {
                    val upTo = minOf(distanceM, d)
                    RoadPath(cut(approach.points, upTo), guided = true, maneuverPoint = if (d <= distanceM) pointAt(approach.points, d) else null)
                }
                // Nothing in the instruction that we understand.
                else -> RoadPath(cut(approach.points, minOf(distanceM, windowEnd)), guided = false)
            }
        }

        val (junction, option) = best
        val prefix = approach.points.subList(0, junction.pointIndex + 1)
        val at = coordinates.getValue(junction.node)
        val remaining = distanceM - junction.along
        if (remaining <= 0) return RoadPath(cut(prefix, distanceM), guided = true, maneuverPoint = at)
        val rest = if (maneuver.turn == TurnDirection.ROUNDABOUT && option.way.isRoundabout) {
            roundabout(at, junction.node, option, remaining, maneuver, bicycle)
        } else {
            walk(at, junction.node, option.to, option.way, remaining, bicycle) { continuation(it, STRAIGHT_ON_DEG) }
        }
        return RoadPath(prefix + rest.points.drop(1), guided = true, maneuverPoint = at)
    }

    /** How well taking [option] at [junction] fits the [maneuver]; null when it doesn't. */
    private fun score(junction: Junction, option: Edge, maneuver: Maneuver, distanceM: Double?): Double? {
        val angle = relativeAngle(junction, option)
        if (abs(angle) > 175) return null
        val named = mentions(maneuver, option.way) && !(sameRoad(option.way, junction.inWay) && maneuver.turn != TurnDirection.STRAIGHT)
        val realJunction = (edges[junction.node]?.size ?: 0) >= 3
        var score = when (val turn = maneuver.turn) {
            null -> if (named) 0.0 else return null
            TurnDirection.ROUNDABOUT -> if (option.way.isRoundabout && !junction.inWay.isRoundabout) 40.0 else return null
            TurnDirection.U_TURN -> return null
            TurnDirection.EXIT -> {
                if (angle !in turn.window!!) return null
                when {
                    option.way.isLink || option.way.tags.containsKey("destination") -> 40.0
                    named && option.way.id != junction.inWay.id -> 20.0
                    else -> return null
                }
            }
            TurnDirection.KEEP_LEFT, TurnDirection.KEEP_RIGHT -> {
                if (angle !in turn.window!!) return null
                val forward = junction.options.map { relativeAngle(junction, it) }.filter { abs(it) <= 60 }
                if (forward.size < 2) return null
                val outermost = if (turn == TurnDirection.KEEP_LEFT) forward.min() else forward.max()
                if (angle != outermost) return null
                40.0
            }
            else -> {
                if (angle !in turn.window!!) return null
                if (!realJunction && !named) return null
                40.0 - abs(angle - turn.nominalDeg!!) / 5
            }
        }
        if (named) score += 100
        if (realJunction) score += 10
        if (distanceM != null) score -= abs(junction.along - distanceM) / 10
        return score
    }

    /** A node we passed, with the ways we could take from there. */
    private class Junction(
        val node: Long,
        val inWay: OsmWay,
        val inBearing: Double,
        /** Distance from the start of the walk. */
        val along: Double,
        val options: List<Edge>,
        /** Index of this node in the walk's points. */
        val pointIndex: Int,
    )

    private class Trace(val points: List<GeoPoint>, val junctions: List<Junction>)

    /**
     * Walks from [start] (between [previous] and [current], heading for [current]) for [budgetM]
     * metres, choosing the way at each node with [choose]; stops where it returns null, at a dead
     * end or on reaching a node twice.
     */
    private fun walk(
        start: GeoPoint,
        previous: Long,
        current: Long,
        way: OsmWay,
        budgetM: Double,
        bicycle: Boolean,
        choose: (Junction) -> Edge?,
    ): Trace {
        val points = mutableListOf(start)
        val junctions = mutableListOf<Junction>()
        val visited = HashSet<Long>()
        var from = start
        var behind = previous
        var ahead = current
        var inWay = way
        var along = 0.0
        while (budgetM - along > 0.01) {
            val to = coordinates.getValue(ahead)
            val leg = GeoMath.distance(from, to)
            if (along + leg >= budgetM) {
                points += GeoMath.destination(from, GeoMath.bearing(from, to), budgetM - along)
                break
            }
            if (leg > 0.01) points += to
            along += leg
            if (!visited.add(ahead)) break
            val inBearing = GeoMath.bearing(coordinates.getValue(behind), to)
            val options = edges[ahead].orEmpty().filter { it.to != behind && allowed(it, bicycle) }
            val junction = Junction(ahead, inWay, inBearing, along, options, points.lastIndex)
            junctions += junction
            val next = choose(junction) ?: break
            behind = ahead
            ahead = next.to
            inWay = next.way
            from = to
        }
        return Trace(points, junctions)
    }

    /** Around a roundabout entered by [entry], leaving by the exit the [maneuver] names or counts. */
    private fun roundabout(start: GeoPoint, from: Long, entry: Edge, budgetM: Double, maneuver: Maneuver, bicycle: Boolean): Trace {
        var exitsSeen = 0
        var left = false
        val trace = walk(start, from, entry.to, entry.way, budgetM, bicycle) { junction ->
            if (!junction.inWay.isRoundabout) return@walk continuation(junction, STRAIGHT_ON_DEG)
            val exits = junction.options.filter { !it.way.isRoundabout }
            if (exits.isNotEmpty()) {
                exitsSeen++
                val chosen = exits.firstOrNull { mentions(maneuver, it.way) }
                    ?: exits.takeIf { maneuver.roundaboutExit == exitsSeen }?.minByOrNull { abs(relativeAngle(junction, it)) }
                if (chosen != null) {
                    left = true
                    return@walk chosen
                }
            }
            junction.options.firstOrNull { it.way.isRoundabout }
        }
        if (left) return trace
        // We don't know where we leave the roundabout: stop where we enter it.
        return Trace(listOf(start), emptyList())
    }

    /**
     * Which way the road continues at [junction]: the same way, else one with the same name or
     * number, else the only one, else straight on within [straightDeg] when that is clearly the
     * straightest. Roundabouts are not entered without an instruction.
     */
    private fun continuation(junction: Junction, straightDeg: Double): Edge? {
        if (junction.inWay.isRoundabout) return null
        val options = junction.options
            .filter { !it.way.isRoundabout }
            .map { it to abs(relativeAngle(junction, it)) }
            .sortedBy { it.second }
        if (options.isEmpty()) return null
        options.firstOrNull { it.first.way.id == junction.inWay.id && it.second <= 120 }?.let { return it.first }
        options.firstOrNull { sameRoad(it.first.way, junction.inWay) && it.second <= 100 }?.let { return it.first }
        if (options.size == 1 && options[0].second <= 100) return options[0].first
        val straightest = options[0]
        val clearly = options.size == 1 || options[1].second - straightest.second >= 25
        return straightest.first.takeIf { straightest.second <= straightDeg && clearly }
    }

    private fun allowed(edge: Edge, bicycle: Boolean): Boolean = when (edge.way.oneway(bicycle)) {
        1 -> edge.forward
        -1 -> !edge.forward
        else -> true
    }

    /** Angle of [option] relative to the direction we arrive at [junction] in; negative is left. */
    private fun relativeAngle(junction: Junction, option: Edge): Double =
        GeoMath.angleDiff(junction.inBearing, branchBearing(junction.node, option))

    /**
     * Direction of a branch, measured to a point some way along it so a tiny first segment (common
     * at junctions) doesn't decide.
     */
    private fun branchBearing(node: Long, edge: Edge): Double {
        val here = coordinates.getValue(node)
        val ids = edge.way.nodeIds
        var index = ids.indexOf(edge.to)
        val step = if (edge.forward) 1 else -1
        var target = coordinates.getValue(edge.to)
        while (GeoMath.distance(here, target) < BRANCH_PROBE_M) {
            index += step
            val id = ids.getOrNull(index) ?: break
            target = coordinates.getValue(id)
        }
        return GeoMath.bearing(here, target)
    }

    internal companion object {
        const val MAX_SNAP_M = 25.0
        const val MAX_MATCH_ANGLE_DEG = 35.0
        const val STRAIGHT_ON_DEG = 30.0
        const val NAVIGATED_STRAIGHT_ON_DEG = 60.0
        const val MIN_TOLERANCE_M = 40.0
        const val UNKNOWN_DISTANCE_WINDOW_M = 1_500.0
        const val BRANCH_PROBE_M = 20.0

        /** The first [lengthM] metres of a polyline. */
        fun cut(points: List<GeoPoint>, lengthM: Double): List<GeoPoint> {
            if (points.size < 2) return points
            val result = mutableListOf(points.first())
            var remaining = lengthM
            for ((a, b) in points.zipWithNext()) {
                val leg = GeoMath.distance(a, b)
                if (leg >= remaining) {
                    if (remaining > 0.01) result += GeoMath.destination(a, GeoMath.bearing(a, b), remaining)
                    return result
                }
                result += b
                remaining -= leg
            }
            return result
        }

        /** The point [distanceM] metres along a polyline, or its end. */
        fun pointAt(points: List<GeoPoint>, distanceM: Double): GeoPoint = cut(points, distanceM).last()
    }
}
