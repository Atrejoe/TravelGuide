package io.github.atrejoe.travelguide.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from north in [0, 360). */
    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    /** The point reached travelling [distanceM] metres from [from] along [bearingDeg]. */
    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(delta) + cos(lat1) * sin(delta) * cos(theta))
        val lon2 = lon1 + atan2(sin(theta) * sin(delta) * cos(lat1), cos(delta) - sin(lat1) * sin(lat2))
        val lonDeg = (Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(lat2).coerceIn(-90.0, 90.0), lonDeg)
    }

    fun normalizeBearing(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Signed smallest difference [to] - [from] in degrees, in (-180, 180]. Positive means clockwise. */
    fun angleDiff(from: Double, to: Double): Double {
        val d = normalizeBearing(to - from)
        return if (d > 180.0) d - 360.0 else d
    }

    /** Average of bearings, handling wrap-around at north. Returns null for an empty input. */
    fun meanBearing(bearings: List<Double>): Double? {
        if (bearings.isEmpty()) return null
        val x = bearings.sumOf { cos(Math.toRadians(it)) }
        val y = bearings.sumOf { sin(Math.toRadians(it)) }
        if (x == 0.0 && y == 0.0) return null
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }
}

/**
 * Flat (equirectangular) projection around [origin]. Accurate to well under a percent within a few
 * tens of kilometres, which is all the look-ahead logic needs.
 */
class LocalProjection(private val origin: GeoPoint) {
    private val metersPerDegLat = Math.toRadians(1.0) * GeoMath.EARTH_RADIUS_M
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))

    /** Returns (east, north) in metres. */
    fun toXY(p: GeoPoint): Pair<Double, Double> {
        var dLon = p.lon - origin.lon
        if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
        return Pair(dLon * metersPerDegLon, (p.lat - origin.lat) * metersPerDegLat)
    }
}
