package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.RelativePosition
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideEngineTest {
    private val start = GeoPoint(52.0, 5.0)
    private val longText = "This is an interesting place with a long history. ".repeat(20)

    private fun poi(id: Long, title: String, point: GeoPoint, type: PoiType, population: Long? = null) =
        Poi(id, title, point, type, population = population, extract = longText)

    /** Drives north at [speedMps] for [seconds], one fix per second. */
    private fun GuideEngine.drive(speedMps: Double, seconds: Int, t0: Long = 0): Long {
        for (s in 0..seconds) {
            onLocation(LocationFix(GeoMath.destination(start, 0.0, speedMps * s), t0 + s * 1000L, speedMps))
        }
        return t0 + seconds * 1000L
    }

    @Test
    fun `driving prefers the town ahead over a building behind`() = runTest {
        val ahead = GeoMath.destination(start, 0.0, 4_000.0)
        val town = poi(1, "Townsville", GeoMath.destination(ahead, 270.0, 2_500.0), PoiType.CITY, population = 20_000)
        val behind = poi(2, "Old Barn", GeoMath.destination(start, 180.0, 2_000.0), PoiType.BUILDING)
        val farOff = poi(3, "Remote Hill", GeoMath.destination(start, 90.0, 9_000.0), PoiType.MOUNTAIN)
        val engine = GuideEngine({ _, _ -> listOf(town, behind, farOff) }, random = Random(1))

        val now = engine.drive(speedMps = 25.0, seconds = 30)
        assertEquals(TravelMode.DRIVING, engine.motion.state!!.mode)
        assertTrue(engine.refreshIfNeeded(now))

        val narration = assertNotNull(engine.nextNarration(now))
        assertEquals("Townsville", narration.poi.title)
        assertIs<RelativePosition.Side>(narration.relative)
        assertTrue((narration.relative as RelativePosition.Side).left)
        assertTrue(narration.sentences.size in 2..5)
    }

    @Test
    fun `waits for the gap and never repeats a story`() = runTest {
        val places = (1..3).map { i ->
            poi(i.toLong(), "Place $i", GeoMath.destination(start, 0.0, 200.0 * i), PoiType.LANDMARK)
        }
        val engine = GuideEngine({ _, _ -> places }, random = Random(1))
        var now = engine.drive(speedMps = 1.4, seconds = 30)
        engine.refreshIfNeeded(now)

        val told = mutableSetOf<String>()
        told += engine.nextNarration(now)!!.poi.title
        engine.onNarrationFinished(now)
        assertNull(engine.nextNarration(now + 5_000), "should respect the minimum gap")

        now += 60_000
        told += engine.nextNarration(now)!!.poi.title
        engine.onNarrationFinished(now)
        now += 60_000
        told += engine.nextNarration(now)!!.poi.title
        assertEquals(3, told.size)
        engine.onNarrationFinished(now)
        assertNull(engine.nextNarration(now + 120_000))
    }

    @Test
    fun `does not refetch while staying in the same area`() = runTest {
        var calls = 0
        val engine = GuideEngine({ _, _ -> calls++; emptyList() })
        val now = engine.drive(speedMps = 1.0, seconds = 20)
        assertTrue(engine.refreshIfNeeded(now))
        assertFalse(engine.refreshIfNeeded(now + 10_000))
        assertEquals(1, calls)
    }
}
