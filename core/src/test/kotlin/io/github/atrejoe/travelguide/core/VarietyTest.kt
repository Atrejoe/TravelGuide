package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.lang.DayPart
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.lang.Wait
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.PhrasePicker
import io.github.atrejoe.travelguide.core.story.RelativePosition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Varied phrasing, a greeting and comforting words while waiting (issue #82). */
class VarietyTest {
    @Test
    fun `a phrasing doesn't come back until the others have had their turn`() {
        val picker = PhrasePicker(Random(1))
        val pool = { title: String -> listOf("Here is $title.", "This is $title.", "Look, $title.", "Welcome to $title.") }
        val firstRound = (1..4).map { pool("Town $it").indexOf(picker.pick("opener", pool("Town $it"))) }
        assertEquals(setOf(0, 1, 2, 3), firstRound.toSet(), "each phrasing once, whatever the place")
        val next = pool("Town 5").indexOf(picker.pick("opener", pool("Town 5")))
        assertEquals(firstRound.first(), next, "then the one used longest ago")
    }

    @Test
    fun `every language has plenty of ways to say things`() {
        for (language in StoryLanguages.all) {
            val name = language.name
            assertTrue(language.aheadOpeners("X", "1 km").size >= 6, name)
            assertTrue(language.sideOpeners("X", left = true, "1 km", window = false).size >= 6, name)
            assertTrue(language.hereOpeners("X", area = true, flying = false).size >= 6, name)
            assertTrue(language.transitions("X", language.where(RelativePosition.Ahead(500.0)), mentioned = false).size >= 6, name)
            assertTrue(language.closings(RelativePosition.Ahead(500.0), TravelMode.DRIVING).size >= 4, name)
            for (part in DayPart.entries) {
                val greetings = language.greetings(part)
                assertTrue(greetings.size >= 10 && greetings.toSet().size == greetings.size, "$name $part")
            }
            for (wait in Wait.entries) assertTrue(language.patience(wait).size >= 4, "$name $wait")
            // Ready at once even on a slow voice: short.
            assertTrue(language.patience(Wait.PREPARING).all { it.split(' ').size <= 6 }, name)
        }
    }

    @Test
    fun `the part of the day follows the clock`() {
        assertEquals(DayPart.MORNING, DayPart.of(7))
        assertEquals(DayPart.AFTERNOON, DayPart.of(14))
        assertEquals(DayPart.EVENING, DayPart.of(20))
        assertEquals(DayPart.NIGHT, DayPart.of(2))
    }
}
