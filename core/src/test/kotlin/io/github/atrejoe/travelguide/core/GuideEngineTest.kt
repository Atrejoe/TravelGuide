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
        assertTrue(assertIs<RelativePosition.Side>(narration.relative).left)
        assertTrue(narration.sentences.size in 2..5, "sentences: ${narration.sentences}")
    }

    @Test
    fun `waits for the gap and never repeats a story`() = runTest {
        // Far enough apart that walking past them gives three separate stories.
        val places = (1..3).map { i ->
            poi(i.toLong(), "Place $i", GeoMath.destination(start, 0.0, 600.0 * i - 300.0), PoiType.LANDMARK)
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

    @Test
    fun `places along the way are combined into one story`() = runTest {
        val castle = poi(10, "Castle Rock", GeoMath.destination(start, 0.0, 2_000.0), PoiType.LANDMARK)
            .copy(description = "Medieval castle", extract = "Castle Rock is a medieval castle built in 1250 by the counts of Holland. " + longText)
        val mill = poi(11, "Old Mill", GeoMath.destination(start, 5.0, 2_400.0), PoiType.LANDMARK)
            .copy(extract = "The Old Mill is the oldest windmill in the region, first mentioned in 1400.")
        val linked = poi(12, "Abbey of Saint Bavo", GeoMath.destination(start, 60.0, 3_000.0), PoiType.LANDMARK)
            .copy(extract = "The Abbey of Saint Bavo was founded by monks in 1102 and destroyed in a siege.")
        val article = "Castle Rock was built in 1250. Its stones came from the Abbey of Saint Bavo after the siege."
        val engine = GuideEngine(
            source = { _, _ -> listOf(castle, mill, linked) },
            articles = { p -> if (p.pageId == 10L) article else null },
            random = Random(2),
        )
        val now = engine.drive(speedMps = 8.0, seconds = 30)
        engine.refreshIfNeeded(now)

        val narration = assertNotNull(engine.nextNarration(now))
        assertEquals("Castle Rock", narration.poi.title)
        assertEquals(setOf("Old Mill", "Abbey of Saint Bavo"), narration.companions.map { it.title }.toSet())
        assertTrue(narration.sentences.any { "Which brings us to Abbey of Saint Bavo" in it || "continues at Abbey of Saint Bavo" in it })
        // All three are told now, so nothing is left for later.
        engine.onNarrationFinished(now)
        assertNull(engine.nextNarration(now + 600_000))
    }

    @Test
    fun `history beats an ordinary municipality`() = runTest {
        val town = poi(20, "Plainville", GeoMath.destination(start, 0.0, 1_500.0), PoiType.CITY, population = 30_000)
            .copy(description = "Municipality in Utrecht, Netherlands")
        val fort = poi(21, "Fort Bakel", GeoMath.destination(start, 0.0, 1_700.0), PoiType.LANDMARK)
            .copy(description = "19th-century fort", extract = "Fort Bakel is a fort built in 1870 as part of the Dutch Water Line. " + longText)
        val engine = GuideEngine({ _, _ -> listOf(town, fort) }, random = Random(1))
        val now = engine.drive(speedMps = 25.0, seconds = 30)
        engine.refreshIfNeeded(now)
        assertEquals("Fort Bakel", engine.nextNarration(now)?.poi?.title)
    }

    @Test
    fun `upcoming stories are listed in the order we reach them`() = runTest {
        val near = poi(30, "Near", GeoMath.destination(start, 0.0, 1_500.0), PoiType.LANDMARK)
        val far = poi(31, "Far", GeoMath.destination(start, 0.0, 6_000.0), PoiType.LANDMARK)
        val engine = GuideEngine({ _, _ -> listOf(far, near) }, random = Random(1))
        val now = engine.drive(speedMps = 25.0, seconds = 30)
        engine.refreshIfNeeded(now)

        val upcoming = engine.upcoming()
        assertEquals(listOf("Near", "Far"), upcoming.map { it.poi.title })
        // 750 m driven; Near is 750 m further at 25 m/s.
        assertEquals(30.0, upcoming.first().etaSeconds!!.toDouble(), 3.0)
    }
}
