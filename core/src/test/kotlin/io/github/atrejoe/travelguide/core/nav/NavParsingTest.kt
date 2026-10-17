package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun resource(name: String): ByteArray =
    NavParsingTest::class.java.getResource("/nav/$name")!!.readBytes()

internal fun assertNear(expected: GeoPoint, actual: GeoPoint, toleranceM: Double = 1.0) {
    val d = GeoMath.distance(expected, actual)
    assertTrue(d <= toleranceM, "Expected $expected but was $actual ($d m away)")
}

class NavParsingTest {

    @Test
    fun `gpx track segments are joined and the track name wins`() {
        val gpx = GpxParser.parse(resource("track-komoot.gpx").inputStream())
        assertEquals("Langs de Vecht", gpx.name)
        assertEquals("https://www.komoot.de", gpx.creator)
        assertEquals(5, gpx.points.size)
        assertEquals(GeoPoint(52.090737, 5.121420), gpx.points.first())
        assertEquals(GeoPoint(52.13, 5.08), gpx.points.last())
    }

    @Test
    fun `gpx without a track uses the route`() {
        val gpx = GpxParser.parse(resource("route-plain.gpx").inputStream())
        assertEquals("Naar Amersfoort", gpx.name)
        assertEquals(3, gpx.points.size)
        assertEquals(GeoPoint(52.1552, 5.3872), gpx.points.last())
    }

    @Test
    fun `gpx with only waypoints and a namespace prefix falls back to the waypoints`() {
        val gpx = GpxParser.parse(resource("waypoints-prefixed.gpx").inputStream())
        assertEquals("Garmin Connect", gpx.creator)
        assertEquals(listOf(GeoPoint(51.9225, 4.47917), GeoPoint(52.0116, 4.35922), GeoPoint(52.0705, 4.3007)), gpx.points)
        assertNull(gpx.name)
    }

    @Test
    fun `empty gpx has no points and broken xml is reported`() {
        assertTrue(GpxParser.parse(resource("empty.gpx").inputStream()).points.isEmpty())
        val e = assertFailsWith<RouteImportException> { GpxParser.parse("<gpx><trk>".byteInputStream()) }
        assertEquals(RouteImportException.Reason.UNSUPPORTED, e.reason)
    }

    @Test
    fun `gpx with an external entity is rejected instead of read`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><trk><name>&x;</name><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>"""
        val result = runCatching { GpxParser.parse(evil.byteInputStream()) }
        assertTrue(result.isFailure || result.getOrThrow().name.orEmpty().isEmpty())
    }

    @Test
    fun `polyline precision 5 decodes Google's documented example`() {
        val points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)), points)
    }

    @Test
    fun `polyline precision 6 decodes and round-trips`() {
        val encoded = "avjjbBwwqwHez|BibfOg_cL`_`]"
        val points = PolylineDecoder.decode(encoded, precision = 6)
        assertEquals(3, points.size)
        assertNear(GeoPoint(52.090737, 5.121420), points[0], 0.2)
        assertNear(GeoPoint(52.155172, 5.387201), points[1], 0.2)
        assertNear(GeoPoint(52.370216, 4.895168), points[2], 0.2)
        assertEquals(encoded, PolylineDecoder.encode(points, precision = 6))
    }

    @Test
    fun `truncated polyline keeps the complete points`() {
        assertEquals(2, PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqN", precision = 5).size)
        assertTrue(PolylineDecoder.decode("", precision = 6).isEmpty())
    }

    @Test
    fun `osrm response is parsed`() {
        val client = OsrmClient({ error("unused") })
        val route = client.parse(resource("osrm-route.json").decodeToString())
        assertEquals(3, route.points.size)
        assertEquals(61234.5, route.distanceM)
        assertEquals(3020.4, route.durationS)
        assertNear(GeoPoint(52.370216, 4.895168), route.points.last(), 0.2)
    }

    @Test
    fun `osrm without a route is a routing error`() {
        val client = OsrmClient({ error("unused") })
        val e = assertFailsWith<RouteImportException> { client.parse(resource("osrm-noroute.json").decodeToString()) }
        assertEquals(RouteImportException.Reason.ROUTING_FAILED, e.reason)
        assertTrue("Impossible route" in e.message.orEmpty())
    }

    @Test
    fun `osrm url uses lon,lat order, the profile and polyline6`() {
        val url = OsrmClient({ "" }).routeUrl(listOf(GeoPoint(52.09, 5.12), GeoPoint(52.37, 4.89)), OsrmProfile.BIKE)
        assertEquals(
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/5.120000,52.090000;4.890000,52.370000" +
                "?overview=full&geometries=polyline6",
            url,
        )
    }

    @Test
    fun `komoot coordinates and name are parsed`() {
        val client = KomootClient({ "" })
        val points = client.parseCoordinates(resource("komoot-coordinates.json").decodeToString())
        assertEquals(4, points.size)
        assertEquals(GeoPoint(47.42079, 10.98475), points.first())
        assertEquals("Rund um die Zugspitze", client.parseName(resource("komoot-tour.json").decodeToString()))
        assertEquals(
            "https://api.komoot.de/v007/tours/42/coordinates?share_token=abc",
            client.coordinatesUrl("42", "abc"),
        )
        assertEquals("https://api.komoot.de/v007/tours/42/coordinates", client.coordinatesUrl("42", null))
    }

    @Test
    fun `nominatim result is parsed`() {
        val client = NominatimClient({ "" })
        val place = client.parse(resource("nominatim-utrecht.json").decodeToString())!!
        assertEquals(GeoPoint(52.0907006, 5.1215634), place.point)
        assertEquals("Utrecht, Netherlands", place.displayName)
        assertNull(client.parse("[]"))
        assertEquals(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=Dom+Tower%2C+Utrecht",
            client.searchUrl("Dom Tower, Utrecht"),
        )
    }

    @Test
    fun `simplifier keeps the shape and drops redundant points`() {
        val start = GeoPoint(52.0, 5.0)
        // A dense straight line north, then east: 1 point every metre.
        val north = (0..1000).map { GeoMath.destination(start, 0.0, it.toDouble()) }
        val corner = north.last()
        val east = (1..1000).map { GeoMath.destination(corner, 90.0, it.toDouble()) }
        val simplified = RouteSimplifier.simplify(north + east)
        assertEquals(3, simplified.size)
        assertNear(start, simplified.first(), 0.01)
        assertNear(corner, simplified[1], 6.0)
        assertEquals(east.last(), simplified.last())
    }

    @Test
    fun `route codec round-trips`() {
        val route = ImportedRoute("A → B", RouteOrigin.GOOGLE_MAPS, listOf(GeoPoint(52.09, 5.12), GeoPoint(52.37, 4.89)), "note")
        val decoded = RouteCodec.decode(RouteCodec.encode(route))!!
        assertEquals(route.name, decoded.name)
        assertEquals(route.origin, decoded.origin)
        assertEquals(route.note, decoded.note)
        assertEquals(route.points, decoded.points)
        assertNull(RouteCodec.decode("garbage"))
    }

    @Test
    fun `summary shows origin, name and length`() {
        val route = ImportedRoute("Langs de Vecht", RouteOrigin.KOMOOT, listOf(GeoPoint(52.0, 5.0), GeoPoint(52.378, 5.0)))
        assertEquals("Komoot tour 'Langs de Vecht' · 42 km", route.summary())
        assertEquals("GPX route · 1.5 km", route.copy(name = null, origin = RouteOrigin.GPX, points = listOf(GeoPoint(52.0, 5.0), GeoPoint(52.0135, 5.0))).summary())
    }

    @Test
    fun `fit course records and name are read`() {
        val points = listOf(GeoPoint(52.090737, 5.121420), GeoPoint(52.1, 5.13), GeoPoint(52.11, 5.14))
        val fit = FitCourseParser.parse(FitWriter.course("Vecht loop", points))
        assertEquals("Vecht loop", fit.name)
        assertEquals(3, fit.points.size)
        points.zip(fit.points).forEach { (e, a) -> assertNear(e, a, 0.05) }
        assertTrue(FitCourseParser.isFit(FitWriter.course("x", points)))
        assertTrue(!FitCourseParser.isFit(resource("route-plain.gpx")))
    }

    @Test
    fun `broken fit is reported`() {
        val bytes = FitWriter.course("x", listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0)))
        val e = assertFailsWith<RouteImportException> { FitCourseParser.parse(bytes.copyOf(30)) }
        assertEquals(RouteImportException.Reason.UNSUPPORTED, e.reason)
    }

    @Test
    fun `navigation notifications from known apps are recognised`() {
        val status = NavigationNotifications.parse(
            "com.google.android.apps.maps", "navigation", ongoing = true,
            title = "200 m", text = "Turn left onto  Main Street",
        )!!
        assertEquals(RouteOrigin.GOOGLE_MAPS, status.app.origin)
        assertEquals("Google Maps: 200 m · Turn left onto Main Street", status.label())
        assertNull(NavigationNotifications.parse("com.whatsapp", null, true, "Hi", "there"))
        assertNull(NavigationNotifications.parse("de.komoot.android", null, ongoing = false, title = "Tour saved", text = null))
        assertEquals("Komoot: navigating", NavigationNotifications.parse("de.komoot.android", null, true, null, " ")!!.label())
        val long = "x".repeat(500)
        assertEquals(140, NavigationNotifications.instruction(long, null)!!.length)
    }
}

/** Writes minimal FIT course files for tests, following the FIT protocol layout. */
internal object FitWriter {
    fun course(name: String, points: List<GeoPoint>): ByteArray {
        val data = ByteArrayOutputStream()
        // Definition: local 0 = course (31), field 5 name, 16-byte string.
        data.write(byteArrayOf(0x40, 0, 0, 31, 0, 1, 5, 16, 7))
        data.write(0x00)
        val nameBytes = name.toByteArray().copyOf(16)
        data.write(nameBytes)
        // Definition: local 1 = record (20), with a developer field: timestamp, lat, lon.
        data.write(byteArrayOf(0x61, 0, 0, 20, 0, 3, 253.toByte(), 4, 0x86.toByte(), 0, 4, 0x85.toByte(), 1, 4, 0x85.toByte()))
        data.write(byteArrayOf(1, 0, 2, 0)) // One 2-byte developer field.
        points.forEachIndexed { i, p ->
            // Alternate normal and compressed-timestamp headers (local type 1 in bits 5-6).
            data.write(if (i % 2 == 0) 0x01 else (0x80 or (1 shl 5) or i))
            data.write(le32(1_000_000_000 + i))
            data.write(le32(semicircles(p.lat)))
            data.write(le32(semicircles(p.lon)))
            data.write(byteArrayOf(0, 0))
        }
        val body = data.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(14, 0x20, 0x08, 0x08))
        out.write(le32(body.size))
        out.write(".FIT".toByteArray())
        out.write(byteArrayOf(0, 0))
        out.write(body)
        out.write(byteArrayOf(0, 0))
        return out.toByteArray()
    }

    private fun semicircles(deg: Double) = Math.round(deg * 2147483648.0 / 180.0).toInt()

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
