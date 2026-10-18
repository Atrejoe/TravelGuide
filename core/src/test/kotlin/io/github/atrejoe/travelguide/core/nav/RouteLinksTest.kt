package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RouteLinksTest {

    @Test
    fun `google api=1 directions with waypoints and travel mode`() {
        val link = RouteLinks.classify(
            "https://www.google.com/maps/dir/?api=1&origin=52.0907,5.1214&destination=Amsterdam+Centraal" +
                "&waypoints=Hilversum%7C52.2,5.2&travelmode=bicycling",
        )
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.Coordinate(GeoPoint(52.0907, 5.1214)), link.origin)
        assertEquals(Waypoint.Place("Amsterdam Centraal"), link.destination)
        assertEquals(listOf(Waypoint.Place("Hilversum"), Waypoint.Coordinate(GeoPoint(52.2, 5.2))), link.waypoints)
        assertEquals(GoogleTravelMode.BICYCLING, link.travelMode)
    }

    @Test
    fun `google api=1 without origin starts at the current location`() {
        val link = RouteLinks.classify("https://www.google.com/maps/dir/?api=1&destination=Utrecht&waypoints=A|B")
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.CurrentLocation, link.origin)
        assertEquals(listOf(Waypoint.Place("A"), Waypoint.Place("B")), link.waypoints)
        assertEquals(null, link.travelMode)
    }

    @Test
    fun `google path form with coordinates, names, viewport and data`() {
        val link = RouteLinks.classify(
            "https://www.google.nl/maps/dir/52.0907,5.1214/Dom+Tower,+Utrecht/Amersfoort/" +
                "@52.12,5.25,11z/data=!3m1!4b1!4m14!4m13!1m0!1m5!1m1!3e2",
        )
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.Coordinate(GeoPoint(52.0907, 5.1214)), link.origin)
        assertEquals(listOf(Waypoint.Place("Dom Tower, Utrecht")), link.waypoints)
        assertEquals(Waypoint.Place("Amersfoort"), link.destination)
        assertEquals(GoogleTravelMode.WALKING, link.travelMode)
    }

    @Test
    fun `google path form with an empty origin means your location`() {
        val link = RouteLinks.classify("https://www.google.com/maps/dir//52.37,4.89/@52.3,4.9,10z")
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.CurrentLocation, link.origin)
        assertEquals(Waypoint.Coordinate(GeoPoint(52.37, 4.89)), link.destination)
        assertEquals(emptyList(), link.waypoints)
    }

    @Test
    fun `google link inside shared text with surrounding words`() {
        val text = "Route to Utrecht Centraal\n\nhttps://www.google.com/maps/dir/Amersfoort/Utrecht+Centraal/. Have fun!"
        val link = RouteLinks.classify(text)
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.Place("Amersfoort"), link.origin)
        assertEquals(Waypoint.Place("Utrecht Centraal"), link.destination)
    }

    @Test
    fun `google legacy saddr and daddr`() {
        val link = RouteLinks.classify("https://maps.google.com/maps?saddr=Utrecht&daddr=Hilversum+to:Amsterdam&dirflg=b")
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.Place("Utrecht"), link.origin)
        assertEquals(listOf(Waypoint.Place("Hilversum")), link.waypoints)
        assertEquals(Waypoint.Place("Amsterdam"), link.destination)
        assertEquals(GoogleTravelMode.BICYCLING, link.travelMode)
    }

    @Test
    fun `google short links and places`() {
        assertIs<RouteLink.ShortLink>(RouteLinks.classify("Directions: https://maps.app.goo.gl/AbCdEf123"))
        assertIs<RouteLink.ShortLink>(RouteLinks.classify("https://goo.gl/maps/xyz"))
        assertIs<RouteLink.GoogleMapsOther>(RouteLinks.classify("https://www.google.com/maps/place/Dom+Tower/@52.09,5.12,17z"))
        assertIs<RouteLink.Unknown>(RouteLinks.classify("https://goo.gl/other"))
    }

    @Test
    fun `google consent page is unwrapped`() {
        val link = RouteLinks.classifyUrl(
            "https://consent.google.com/m?continue=https://www.google.com/maps/dir/Utrecht/Amsterdam&gl=NL",
        )
        assertIs<RouteLink.GoogleMapsDirections>(link)
        assertEquals(Waypoint.Place("Amsterdam"), link.destination)
    }

    @Test
    fun `komoot tour links with and without locale and share token`() {
        val plain = RouteLinks.classify("https://www.komoot.com/tour/123456789")
        assertEquals(RouteLink.KomootTour("https://www.komoot.com/tour/123456789", "123456789", null), plain)

        val shared = RouteLinks.classify(
            "Check out this tour on komoot: https://www.komoot.com/nl-nl/tour/987654?share_token=aBcD123&ref=wtd",
        )
        assertIs<RouteLink.KomootTour>(shared)
        assertEquals("987654", shared.id)
        assertEquals("aBcD123", shared.shareToken)

        val german = RouteLinks.classify("https://www.komoot.de/de-de/tour/555?ref=itd")
        assertIs<RouteLink.KomootTour>(german)
        assertEquals("555", german.id)
        assertEquals(null, german.shareToken)

        assertIs<RouteLink.KomootSmartTour>(RouteLinks.classify("https://www.komoot.com/smarttour/e926612355"))
        assertIs<RouteLink.Unknown>(RouteLinks.classify("https://www.komoot.com/user/123"))
    }

    @Test
    fun `garmin course links are recognised`() {
        val modern = RouteLinks.classify("https://connect.garmin.com/modern/course/312345678")
        assertEquals(RouteLink.GarminCourse("https://connect.garmin.com/modern/course/312345678", "312345678"), modern)
        val app = RouteLinks.classify("My course https://connect.garmin.com/app/course/42?share=1")
        assertIs<RouteLink.GarminCourse>(app)
        assertEquals("42", app.id)
        assertIs<RouteLink.GarminCourse>(RouteLinks.classify("https://explore.garmin.com/Share/ABC123"))
        assertIs<RouteLink.Unknown>(RouteLinks.classify("https://www.garmin.com/en-US/p/123"))
    }

    @Test
    fun `text without links is unknown`() {
        assertIs<RouteLink.Unknown>(RouteLinks.classify("just some words"))
        assertIs<RouteLink.Unknown>(RouteLinks.classify("https://example.com/route"))
    }
}
