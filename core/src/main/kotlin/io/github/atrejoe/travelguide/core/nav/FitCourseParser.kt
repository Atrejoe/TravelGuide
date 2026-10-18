package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint

/** What a FIT course (or activity) file holds. */
data class FitRoute(val name: String?, val points: List<GeoPoint>)

/**
 * A minimal reader for Garmin FIT files, enough to get the positions out of a course exported
 * from Garmin Connect or Garmin Explore (or a recorded activity).
 *
 * Reads `record` messages (global number 20, fields 0/1 = position_lat/position_long) and, when
 * there are none, `course_point` messages (32, fields 2/3). Positions are in semicircles:
 * degrees = semicircles × 180 / 2³¹. The course name comes from the `course` message (31, field 5).
 *
 * Implemented from the public FIT protocol description rather than with the FIT SDK to keep the
 * app small; developer fields and compressed timestamp headers are understood and skipped.
 */
object FitCourseParser {

    private const val RECORD = 20
    private const val COURSE = 31
    private const val COURSE_POINT = 32
    private const val INVALID_SINT32 = 0x7FFFFFFF

    /** True when [bytes] start with a FIT file header. */
    fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    /** Throws [RouteImportException] when the file is not a readable FIT file. */
    fun parse(bytes: ByteArray): FitRoute {
        if (!isFit(bytes)) throw unreadable()
        val records = ArrayList<GeoPoint>()
        val coursePoints = ArrayList<GeoPoint>()
        var name: String? = null

        var fileStart = 0
        // FIT files may be chained; read each one in turn.
        while (fileStart + 12 <= bytes.size && isFitAt(bytes, fileStart)) {
            val headerSize = bytes[fileStart].toInt() and 0xff
            val dataSize = u32le(bytes, fileStart + 4)
            if (headerSize < 12) throw unreadable()
            var pos = fileStart + headerSize
            val end = minOf(bytes.size.toLong(), pos.toLong() + dataSize).toInt()
            val definitions = arrayOfNulls<Definition>(16)

            while (pos < end) {
                val header = bytes[pos++].toInt() and 0xff
                val compressed = (header and 0x80) != 0
                if (!compressed && (header and 0x40) != 0) {
                    val local = header and 0x0f
                    val hasDeveloperFields = (header and 0x20) != 0
                    if (pos + 5 > end) throw unreadable()
                    val bigEndian = bytes[pos + 1].toInt() == 1
                    val global = if (bigEndian) u16be(bytes, pos + 2) else u16le(bytes, pos + 2)
                    val fieldCount = bytes[pos + 4].toInt() and 0xff
                    pos += 5
                    val fields = ArrayList<Field>(fieldCount)
                    repeat(fieldCount) {
                        if (pos + 3 > end) throw unreadable()
                        fields += Field(bytes[pos].toInt() and 0xff, bytes[pos + 1].toInt() and 0xff)
                        pos += 3
                    }
                    var developerBytes = 0
                    if (hasDeveloperFields) {
                        if (pos >= end) throw unreadable()
                        val count = bytes[pos++].toInt() and 0xff
                        repeat(count) {
                            if (pos + 3 > end) throw unreadable()
                            developerBytes += bytes[pos + 1].toInt() and 0xff
                            pos += 3
                        }
                    }
                    definitions[local] = Definition(global, bigEndian, fields, developerBytes)
                    continue
                }
                // A data message; compressed timestamp headers keep the local type in bits 5-6.
                val local = if (compressed) (header shr 5) and 0x03 else header and 0x0f
                val def = definitions[local] ?: throw unreadable()
                var lat: Int? = null
                var lon: Int? = null
                pos = readData(bytes, pos, def) { fieldNum, offset, size ->
                    when (def.global) {
                        RECORD -> when (fieldNum) {
                            0 -> lat = sint32(bytes, offset, size, def.bigEndian)
                            1 -> lon = sint32(bytes, offset, size, def.bigEndian)
                        }
                        COURSE_POINT -> when (fieldNum) {
                            2 -> lat = sint32(bytes, offset, size, def.bigEndian)
                            3 -> lon = sint32(bytes, offset, size, def.bigEndian)
                        }
                        COURSE -> if (fieldNum == 5 && name == null) name = string(bytes, offset, size)
                    }
                }
                val point = semicircles(lat ?: continue, lon ?: continue) ?: continue
                when (def.global) {
                    RECORD -> records += point
                    COURSE_POINT -> coursePoints += point
                }
            }
            // Skip the 2-byte file CRC and continue with a chained file, if any.
            fileStart = end + 2
        }
        return FitRoute(name?.takeIf { it.isNotBlank() }, if (records.size >= 2) records else coursePoints)
    }

    private class Field(val num: Int, val size: Int)

    private class Definition(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val developerBytes: Int)

    /** Calls [onField] with (field number, offset, size) for each field and returns the position after the message. */
    private inline fun readData(bytes: ByteArray, start: Int, def: Definition, onField: (Int, Int, Int) -> Unit): Int {
        var pos = start
        for (field in def.fields) {
            if (pos + field.size > bytes.size) throw unreadable()
            onField(field.num, pos, field.size)
            pos += field.size
        }
        pos += def.developerBytes
        if (pos > bytes.size) throw unreadable()
        return pos
    }

    private fun semicircles(lat: Int, lon: Int): GeoPoint? {
        if (lat == INVALID_SINT32 || lon == INVALID_SINT32) return null
        val la = lat * 180.0 / 2147483648.0
        val lo = lon * 180.0 / 2147483648.0
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        return GeoPoint(la, lo)
    }

    private fun sint32(bytes: ByteArray, offset: Int, size: Int, bigEndian: Boolean): Int? {
        if (size != 4) return null
        return if (bigEndian) {
            ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
        } else {
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }
    }

    private fun string(bytes: ByteArray, offset: Int, size: Int): String {
        var length = 0
        while (length < size && bytes[offset + length] != 0.toByte()) length++
        return String(bytes, offset, length, Charsets.UTF_8)
    }

    private fun isFitAt(bytes: ByteArray, start: Int): Boolean =
        bytes[start + 8] == '.'.code.toByte() && bytes[start + 9] == 'F'.code.toByte() &&
            bytes[start + 10] == 'I'.code.toByte() && bytes[start + 11] == 'T'.code.toByte()

    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32le(b: ByteArray, i: Int): Long =
        (u16le(b, i).toLong()) or (u16le(b, i + 2).toLong() shl 16)

    private fun unreadable() = RouteImportException(
        RouteImportException.Reason.UNSUPPORTED,
        "This FIT file could not be read. Export the course as a GPX file instead.",
    )
}
