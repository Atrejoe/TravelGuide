package io.github.atrejoe.travelguide.ui

import io.github.atrejoe.travelguide.MapPlace
import io.github.atrejoe.travelguide.StoryItem
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportStoryTest {
    private val story = StoryItem(
        title = "Teylers Museum",
        text = "The oldest museum in the Netherlands.",
        url = "https://nl.wikipedia.org/wiki/Teylers_Museum",
        timeMillis = 0,
        places = listOf(MapPlace("Teylers Museum", GeoPoint(52.3803, 4.6406))),
        toldBy = "Retold by Claude Opus 5.5",
    )

    @Test
    fun `the report carries the reason, the comment and the story`() {
        val message = reportMessage(story, ReportReason.WRONG, "  It closed in 1900?  ")
        assertTrue(message.startsWith("Story reported as wrong."))
        assertTrue("Comment: It closed in 1900?" in message)
        assertTrue("Teylers Museum" in message)
        assertTrue("Retold by Claude Opus 5.5" in message)
        assertTrue(message.endsWith("The oldest museum in the Netherlands."))
    }

    @Test
    fun `an empty comment is left out`() {
        assertFalse("Comment:" in reportMessage(story, ReportReason.OFFENSIVE, " "))
    }

    @Test
    fun `the report never carries the location`() {
        val all = reportMessage(story, ReportReason.OTHER, "") + reportTags(story, ReportReason.OTHER).values
        assertFalse("52.38" in all)
        assertFalse("4.64" in all)
    }

    @Test
    fun `tags say why and which story`() {
        val tags = reportTags(story, ReportReason.OFFENSIVE)
        assertEquals("offensive", tags["report.reason"])
        assertEquals("Teylers Museum", tags["report.title"])
        assertEquals("Retold by Claude Opus 5.5", tags["report.told_by"])
    }
}
