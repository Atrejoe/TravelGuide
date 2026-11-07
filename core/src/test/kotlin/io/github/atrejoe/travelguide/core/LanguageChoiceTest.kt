package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.lang.LanguageChoice
import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone's language, else another one set up on it, else English (issue #92). */
class LanguageChoiceTest {
    @Test
    fun `the first language the app speaks wins`() {
        assertEquals("nl", LanguageChoice.pick(listOf("nl-BE", "en-GB")))
        assertEquals("pt", LanguageChoice.pick(listOf("pt_BR")))
    }

    @Test
    fun `an unknown phone language falls back to a keyboard language, then English`() {
        // A Polish phone with a German keyboard.
        assertEquals("de", LanguageChoice.pick(listOf("pl-PL", "de-DE")))
        assertEquals("en", LanguageChoice.pick(listOf("pl-PL", "ja-JP")))
        assertEquals("en", LanguageChoice.pick(emptyList()))
    }
}
