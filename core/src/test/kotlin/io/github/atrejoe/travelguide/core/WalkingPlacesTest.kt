package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.places.PlaceSource
import io.github.atrejoe.travelguide.core.places.TownSource
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceFacts
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.story.RelativePosition
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkingPlacesTest {
    private val start = GeoPoint(52.0, 5.0)
    private val longText = "This is an interesting place with a long history, founded in 1122 by monks. ".repeat(10)

    private fun GuideEngine.move(speedMps: Double, seconds: Int, t0: Long = 0, from: GeoPoint = start): Long {
        for (s in 0..seconds) {
            onLocation(LocationFix(GeoMath.destination(from, 0.0, speedMps * s), t0 + s * 1000L, speedMps))
        }
        return t0 + seconds * 1000L
    }

    /** A building [aheadM] metres north and [sideM] metres east of the start. */
    private fun building(id: Long, name: String, aheadM: Double, sideM: Double, qid: String? = null) = Poi(
        -id, name, GeoMath.destination(GeoMath.destination(start, 0.0, aheadM), 90.0, sideM), PoiType.BUILDING,
        description = "weigh house", url = "https://www.openstreetmap.org/way/$id", wikidataId = qid,
        facts = PlaceFacts(
            PlaceKind.HISTORIC_BUILDING, since = HistoricDate(1488, HistoricDate.Precision.YEAR),
            heritage = "a Rijksmonument, a listed national monument",
        ),
    )

    @Test
    fun `tells about a building without an article while walking past`() = runTest {
        val waag = building(1, "Waag", aheadM = 120.0, sideM = 40.0)
        val engine = GuideEngine({ _, _ -> emptyList() }, places = { _, _ -> listOf(waag) }, random = Random(1))
        val now = engine.move(1.4, 30)
        assertEquals(TravelMode.WALKING, engine.motion.state!!.mode)
        assertTrue(engine.refreshIfNeeded(now))

        val narration = assertNotNull(engine.nextNarration(now))
        assertEquals("Waag", narration.poi.title)
        assertTrue(narration.sentences.contains("It is a weigh house."), "${narration.sentences}")
        assertTrue(narration.sentences.any { "1488" in it })
        assertTrue(narration.sentences.any { "Rijksmonument" in it })
    }

    @Test
    fun `a building out of sight is not told`() = runTest {
        val far = building(2, "Far Away Hall", aheadM = 100.0, sideM = 450.0)
        val engine = GuideEngine({ _, _ -> emptyList() }, places = { _, _ -> listOf(far) }, random = Random(1))
        val now = engine.move(1.4, 30)
        engine.refreshIfNeeded(now)
        assertNull(engine.nextNarration(now))
    }

    @Test
    fun `a building covered by a Wikipedia article is told once, from the article`() = runTest {
        val article = Poi(
            77, "Dom Tower of Utrecht", GeoMath.destination(start, 0.0, 150.0), PoiType.LANDMARK,
            extract = longText, wikidataId = "Q1404",
        )
        val sameItem = building(3, "Domtoren", aheadM = 160.0, sideM = 10.0, qid = "Q1404")
        val sameName = building(4, "Dom Tower of Utrecht", aheadM = 170.0, sideM = 20.0)
        val engine = GuideEngine(
            { _, _ -> listOf(article) },
            places = { _, _ -> listOf(sameItem, sameName) },
            random = Random(1),
        )
        val now = engine.move(1.4, 30)
        engine.refreshIfNeeded(now)
        assertEquals(1, engine.knownPlaces)
        assertEquals(77L, engine.nextNarration(now)?.poi?.pageId)
        engine.onNarrationFinished(now)
        assertNull(engine.nextNarration(now + 600_000))
    }

    @Test
    fun `the town is welcomed first, and only once per visit`() = runTest {
        val town = Poi(
            500, "Utrecht", GeoMath.destination(start, 45.0, 3_000.0), PoiType.CITY, population = 360_000,
            extract = "Utrecht is a city in the Netherlands. The Romans built a fort here around 50 AD. " + longText,
            wikidataId = "Q803",
        )
        val waag = building(5, "Waag", aheadM = 120.0, sideM = 30.0)
        var lookups = 0
        val engine = GuideEngine(
            { _, _ -> emptyList() },
            places = { _, _ -> listOf(waag) },
            towns = TownSource { lookups++; town },
            random = Random(4),
        )
        var now = engine.move(1.4, 30)
        engine.refreshIfNeeded(now)

        val welcome = assertNotNull(engine.nextNarration(now))
        assertEquals("Utrecht", welcome.poi.title)
        assertEquals(RelativePosition.Here, welcome.relative)
        assertEquals(town, engine.currentTown)
        engine.onNarrationFinished(now)

        now += 60_000
        assertEquals("Waag", engine.nextNarration(now)?.poi?.title)
        engine.onNarrationFinished(now)

        // Walk on for half a kilometre: the town is looked up again but not told again.
        now = engine.move(1.4, 400, t0 = now, from = engine.motion.state!!.fix.point)
        engine.refreshIfNeeded(now)
        assertEquals(2, lookups)
        assertNull(engine.nextNarration(now + 60_000))
    }

    @Test
    fun `nothing is fetched while driving or when switched off`() = runTest {
        var calls = 0
        val places = PlaceSource { _, _ -> calls++; emptyList() }
        val towns = TownSource { calls++; null }
        val driving = GuideEngine({ _, _ -> emptyList() }, places = places, towns = towns)
        driving.refreshIfNeeded(driving.move(25.0, 30))
        assertEquals(0, calls)

        val off = GuideEngine({ _, _ -> emptyList() }, places = places, towns = towns).apply { walkingPlaces = false }
        off.refreshIfNeeded(off.move(1.4, 30))
        assertEquals(0, calls)
    }

    @Test
    fun `failures back off and a fetch is not repeated nearby`() = runTest {
        var calls = 0
        var failing = true
        val engine = GuideEngine(
            { _, _ -> emptyList() },
            places = { _, _ ->
                calls++
                if (failing) throw IOException("Overpass busy")
                emptyList()
            },
        )
        val now = engine.move(1.4, 30)
        engine.refreshIfNeeded(now)
        assertEquals(1, calls)
        engine.refreshIfNeeded(now + 10_000)
        assertEquals(1, calls, "waits before trying again")
        failing = false
        engine.refreshIfNeeded(now + 50_000)
        assertEquals(2, calls)
        engine.refreshIfNeeded(now + 200_000)
        assertEquals(2, calls, "still in the same area")
    }
}
