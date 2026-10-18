package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TowardRouterTest {
    private val requests = mutableListOf<String>()
    private val http = HttpFetcher { url ->
        requests += url
        when {
            "nominatim" in url && "Nergensdorp" in url -> "[]"
            "nominatim" in url -> resource("nominatim-utrecht.json").decodeToString()
            else -> resource("osrm-route.json").decodeToString()
        }
    }
    private val router = TowardRouter(NominatimClient(http, clock = { 0 }), OsrmClient(http))
    private val here = GeoPoint(52.0, 5.0)

    @Test
    fun `places are looked up near us once and routed to`() = runTest {
        val route = assertNotNull(router.route(here, "Utrecht", TravelMode.CYCLING))
        assertTrue(route.size >= 2)
        assertTrue("viewbox=" in requests[0] && "bounded=1" in requests[0], requests[0])
        assertTrue("routed-bike" in requests[1], requests[1])

        router.route(here, "utrecht", TravelMode.DRIVING)
        assertEquals(1, requests.count { "nominatim" in it }, "The place is cached by name")
        assertTrue("routed-car" in requests.last())
    }

    @Test
    fun `unknown and nearby places give no route`() = runTest {
        assertNull(router.route(here, "Nergensdorp", TravelMode.DRIVING))
        assertNull(router.route(GeoPoint(52.0907, 5.1216), "Utrecht", TravelMode.DRIVING))
        assertEquals(0, requests.count { "routed" in it })
    }
}
