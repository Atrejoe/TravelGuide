package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Serves fixtures by URL prefix and records what was requested. */
private class FakeHttp(private val routes: Map<String, () -> String>) : HttpFetcher {
    val requested = mutableListOf<String>()
    override suspend fun get(url: String): String {
        requested += url
        val handler = routes.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("HTTP 404 for $url")
        return handler()
    }
}

class RouteImporterTest {
    private val osrm = resource("osrm-route.json").decodeToString()
    private val nominatim = resource("nominatim-utrecht.json").decodeToString()

    @Test
    fun `google maps directions with a place name are geocoded and routed`() = runTest {
        val http = FakeHttp(
            mapOf(
                "https://nominatim.openstreetmap.org/search" to { nominatim },
                "https://routing.openstreetmap.de/routed-foot/" to { osrm },
            ),
        )
        val route = RouteImporter(http).importText(
            "https://www.google.com/maps/dir/?api=1&origin=52.37,4.89&destination=Utrecht&travelmode=walking",
        )
        assertEquals(RouteOrigin.GOOGLE_MAPS, route.origin)
        assertEquals("start → Utrecht", route.name)
        assertEquals(3, route.points.size)
        assertTrue(route.note!!.contains("OpenStreetMap"))
        assertEquals(2, http.requested.size)
        assertTrue(http.requested[0].endsWith("q=Utrecht"))
        assertTrue(http.requested[1].contains("/4.890000,52.370000;5.121563,52.090701?"), http.requested[1])
    }

    @Test
    fun `short links are resolved first and the guide's mode picks the profile`() = runTest {
        val http = FakeHttp(mapOf("https://routing.openstreetmap.de/routed-bike/" to { osrm }))
        val resolver = LinkResolver { "https://www.google.com/maps/dir/52.09,5.12/52.37,4.89/@52.2,5.0,10z" }
        val route = RouteImporter(http, resolver).importText(
            "Utrecht to Amsterdam https://maps.app.goo.gl/AbC123",
            mode = TravelMode.CYCLING,
        )
        assertEquals(RouteOrigin.GOOGLE_MAPS, route.origin)
        assertEquals(1, http.requested.size)
    }

    @Test
    fun `route from your location needs a position`() = runTest {
        val http = FakeHttp(mapOf("https://routing.openstreetmap.de/routed-car/" to { osrm }))
        val importer = RouteImporter(http)
        val e = assertFailsWith<RouteImportException> { importer.importText("https://www.google.com/maps/dir//52.37,4.89") }
        assertEquals(RouteImportException.Reason.NEEDS_LOCATION, e.reason)

        val route = importer.importText("https://www.google.com/maps/dir//52.37,4.89", here = GeoPoint(52.09, 5.12))
        assertEquals("your location → destination", route.name)
        assertTrue(http.requested.single().contains("/5.120000,52.090000;4.890000,52.370000?"))
    }

    @Test
    fun `unknown places are reported`() = runTest {
        val http = FakeHttp(mapOf("https://nominatim.openstreetmap.org/" to { "[]" }))
        val e = assertFailsWith<RouteImportException> {
            RouteImporter(http).importText("https://www.google.com/maps/dir/Nowhere+Land/52.37,4.89")
        }
        assertEquals(RouteImportException.Reason.GEOCODING_FAILED, e.reason)
        assertTrue("Nowhere Land" in e.message!!)
    }

    @Test
    fun `komoot tour is fetched with its share token`() = runTest {
        val http = FakeHttp(
            mapOf(
                "https://api.komoot.de/v007/tours/123456789/coordinates" to { resource("komoot-coordinates.json").decodeToString() },
                "https://api.komoot.de/v007/tours/123456789" to { resource("komoot-tour.json").decodeToString() },
            ),
        )
        val route = RouteImporter(http).importText("https://www.komoot.com/de-de/tour/123456789?share_token=tok&ref=wtd")
        assertEquals(RouteOrigin.KOMOOT, route.origin)
        assertEquals("Rund um die Zugspitze", route.name)
        assertTrue(route.points.size >= 2)
        assertEquals("https://api.komoot.de/v007/tours/123456789/coordinates?share_token=tok", http.requested.first())
    }

    @Test
    fun `private komoot tour suggests a gpx export`() = runTest {
        val e = assertFailsWith<RouteImportException> {
            RouteImporter(FakeHttp(emptyMap())).importText("https://www.komoot.com/tour/1")
        }
        assertEquals(RouteImportException.Reason.KOMOOT_UNAVAILABLE, e.reason)
        assertTrue("GPX" in e.message!!)
    }

    @Test
    fun `garmin course links ask for a gpx export`() = runTest {
        val e = assertFailsWith<RouteImportException> {
            RouteImporter(FakeHttp(emptyMap())).importText("https://connect.garmin.com/modern/course/312345678")
        }
        assertEquals(RouteImportException.Reason.GARMIN_LOGIN_REQUIRED, e.reason)
        assertTrue("GPX" in e.message!!)
    }

    @Test
    fun `unrelated text is unsupported`() = runTest {
        val e = assertFailsWith<RouteImportException> { RouteImporter(FakeHttp(emptyMap())).importText("hello") }
        assertEquals(RouteImportException.Reason.UNSUPPORTED, e.reason)
    }

    @Test
    fun `gpx files keep their name and tell komoot, garmin and osmand apart`() {
        val importer = RouteImporter(FakeHttp(emptyMap()))
        val komoot = importer.importFile(resource("track-komoot.gpx"), "export.gpx")
        assertEquals(RouteOrigin.KOMOOT, komoot.origin)
        assertEquals("Langs de Vecht", komoot.name)
        // From 52.10,5.11 on the points lie on a straight line, so the ones in between are dropped.
        assertEquals(listOf(GeoPoint(52.090737, 5.121420), GeoPoint(52.1, 5.11), GeoPoint(52.13, 5.08)), komoot.points)

        assertEquals(RouteOrigin.OSMAND, importer.importFile(resource("route-plain.gpx")).origin)
        val garmin = importer.importFile(resource("waypoints-prefixed.gpx"), "Rotterdam-Den-Haag.gpx")
        assertEquals(RouteOrigin.GARMIN, garmin.origin)
        assertEquals("Rotterdam-Den-Haag", garmin.name)
    }

    @Test
    fun `empty gpx is reported`() {
        val e = assertFailsWith<RouteImportException> { RouteImporter(FakeHttp(emptyMap())).importFile(resource("empty.gpx")) }
        assertEquals(RouteImportException.Reason.NO_ROUTE, e.reason)
    }

    @Test
    fun `fit course files are imported`() {
        val points = listOf(GeoPoint(52.0, 5.0), GeoPoint(52.01, 5.02), GeoPoint(52.03, 5.0))
        val route = RouteImporter(FakeHttp(emptyMap())).importFile(FitWriter.course("Rondje", points), "course.fit")
        assertEquals(RouteOrigin.FIT, route.origin)
        assertEquals("Rondje", route.name)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `osrm profile follows google's travel mode first`() {
        assertEquals(OsrmProfile.FOOT, RouteImporter.profileFor(GoogleTravelMode.WALKING, TravelMode.DRIVING))
        assertEquals(OsrmProfile.BIKE, RouteImporter.profileFor(null, TravelMode.CYCLING))
        assertEquals(OsrmProfile.CAR, RouteImporter.profileFor(null, null))
        assertEquals(OsrmProfile.CAR, RouteImporter.profileFor(GoogleTravelMode.TRANSIT, TravelMode.WALKING))
    }
}
