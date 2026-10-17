package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Google's encoded polyline format, as used by OSRM. Precision 5 is the classic Google format,
 * precision 6 is what OSRM returns for `geometries=polyline6`.
 */
object PolylineDecoder {

    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = 10.0.pow(precision)
        val points = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, afterLat) = nextValue(encoded, index) ?: break
            val (dLon, afterLon) = nextValue(encoded, afterLat) ?: break
            index = afterLon
            lat += dLat
            lon += dLon
            val la = lat / factor
            val lo = lon / factor
            if (la in -90.0..90.0 && lo in -180.0..180.0) points += GeoPoint(la, lo)
        }
        return points
    }

    fun encode(points: List<GeoPoint>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            writeValue(out, lat - lastLat)
            writeValue(out, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    /** Returns the decoded value and the index after it, or null when the input is truncated. */
    private fun nextValue(s: String, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < s.length) {
            val b = s[i++].code - 63
            if (b < 0 || shift > 60) return null
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) {
                val value = if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
                return value to i
            }
        }
        return null
    }

    private fun writeValue(out: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }
}
