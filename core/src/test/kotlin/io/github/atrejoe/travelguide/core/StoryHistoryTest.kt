package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.guide.HistoryRetention
import io.github.atrejoe.travelguide.core.guide.StoryLog
import io.github.atrejoe.travelguide.core.guide.ToldStory
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories remembered across trips (issue #84). */
class StoryHistoryTest {
    private val day = 24 * 60 * 60 * 1000L
    private var now = 400 * day
    private val start = GeoPoint(52.0, 5.0)

    private fun told(id: Long, daysAgo: Int, item: String? = null) =
        ToldStory("Place $id", "en", id, item, now - daysAgo * day, sentences = listOf("A story."))

    private fun poi(id: Long, item: String? = null, lang: String = "en") = Poi(
        id, "Place $id", GeoMath.destination(start, 0.0, 300.0), PoiType.LANDMARK, lang = lang, wikidataId = item,
        extract = "Place $id is a tower built in 1488. According to legend a giant built it in one night. ".repeat(4),
    )

    @Test
    fun `a place told before is recognised, in any language`() {
        val log = StoryLog(clock = { now })
        log.add(told(1, daysAgo = 2, item = "Q1"))
        assertTrue(log.wasTold(poi(1)))
        assertTrue(log.wasTold(poi(99, item = "Q1", lang = "nl")), "the same place in another Wikipedia")
        assertFalse(log.wasTold(poi(1, lang = "nl")), "page ids differ between Wikipedias")
        assertFalse(log.wasTold(poi(2)))
    }

    @Test
    fun `stories are forgotten after the retention`() {
        val log = StoryLog(HistoryRetention.WEEK, clock = { now })
        log.restore(listOf(told(1, daysAgo = 10), told(2, daysAgo = 3)))
        assertEquals(listOf(2L), log.entries.map { it.pageId })
        log.retention = HistoryRetention.ALWAYS
        log.add(told(3, daysAgo = 300))
        log.retention = HistoryRetention.MONTH
        assertEquals(listOf(2L), log.entries.map { it.pageId })
    }

    @Test
    fun `only the trip remembers nothing, and erasing forgets all`() {
        val trip = StoryLog(HistoryRetention.TRIP, clock = { now })
        trip.add(told(1, daysAgo = 0))
        assertEquals(0, trip.size)
        val log = StoryLog(HistoryRetention.ALWAYS, maxStories = 2, clock = { now })
        (1L..3L).forEach { log.add(told(it, daysAgo = 0)) }
        assertEquals(listOf(2L, 3L), log.entries.map { it.pageId }, "the oldest goes beyond the limit")
        log.clear()
        assertFalse(log.wasTold(poi(3)))
    }

    @Test
    fun `the guide doesn't tell again what an earlier trip told`() = runTest {
        val log = StoryLog(clock = { now })
        log.add(told(1, daysAgo = 1))
        val places = listOf(poi(1), poi(2))
        val engine = GuideEngine({ _, _ -> places }, history = log, random = Random(1))
        for (s in 0..30) engine.onLocation(LocationFix(GeoMath.destination(start, 0.0, 1.4 * s), s * 1000L, 1.4))
        engine.refreshIfNeeded(30_000)
        assertEquals("Place 2", assertNotNull(engine.nextNarration(30_000)).poi.title)
        assertNull(engine.nextNarration(30_000 + 600_000))
    }
}
