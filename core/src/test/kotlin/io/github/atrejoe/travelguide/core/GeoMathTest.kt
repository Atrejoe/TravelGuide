package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoMathTest {
    private val amsterdam = GeoPoint(52.3728, 4.8936)
    private val utrecht = GeoPoint(52.0907, 5.1214)

    @Test
    fun `distance between Amsterdam and Utrecht is about 35 km`() {
        assertEquals(34_900.0, GeoMath.distance(amsterdam, utrecht), 600.0)
    }

    @Test
    fun `destination round-trips with bearing and distance`() {
        val bearing = GeoMath.bearing(amsterdam, utrecht)
        val distance = GeoMath.distance(amsterdam, utrecht)
        val reached = GeoMath.destination(amsterdam, bearing, distance)
        assertEquals(0.0, GeoMath.distance(reached, utrecht), 5.0)
    }

    @Test
    fun `angle difference wraps around north`() {
        assertEquals(20.0, GeoMath.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.angleDiff(10.0, 350.0), 1e-9)
        assertEquals(0.0, GeoMath.meanBearing(listOf(350.0, 10.0))!! % 360.0, 1e-6)
    }
}
