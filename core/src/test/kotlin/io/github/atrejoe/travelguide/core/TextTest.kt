package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.story.SentenceSplitter
import io.github.atrejoe.travelguide.core.story.SpeechChunker
import io.github.atrejoe.travelguide.core.story.TextCleaner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun `cleaner removes pronunciations and citation marks`() {
        val raw = "Utrecht (Dutch pronunciation: [ˈytrɛxt] (listen)) is the fourth-largest city [1] of the Netherlands ."
        assertEquals("Utrecht is the fourth-largest city of the Netherlands.", TextCleaner.clean(raw))
    }

    @Test
    fun `splitter respects abbreviations and initials`() {
        val text = "St. Martin's Cathedral was built in c. 1254. It was designed by J. van Campen. It is tall!"
        assertEquals(
            listOf(
                "St. Martin's Cathedral was built in c. 1254.",
                "It was designed by J. van Campen.",
                "It is tall!",
            ),
            SentenceSplitter.split(text),
        )
        assertEquals(
            listOf("It was granted city rights by Emperor Henry V.", "The Dom Tower is tall."),
            SentenceSplitter.split("It was granted city rights by Emperor Henry V. The Dom Tower is tall."),
        )
    }

    @Test
    fun `distance phrases read naturally`() {
        assertEquals("100 metres", NarrationComposer.distancePhrase(40.0))
        assertEquals("400 metres", NarrationComposer.distancePhrase(420.0))
        assertEquals("a kilometre", NarrationComposer.distancePhrase(1_010.0))
        assertEquals("2.5 kilometres", NarrationComposer.distancePhrase(2_480.0))
        assertEquals("3 kilometres", NarrationComposer.distancePhrase(3_020.0))
        assertEquals("12 kilometres", NarrationComposer.distancePhrase(12_300.0))
    }

    @Test
    fun `compass names`() {
        assertEquals("north", NarrationComposer.compassName(355.0))
        assertEquals("east", NarrationComposer.compassName(95.0))
        assertEquals("southwest", NarrationComposer.compassName(220.0))
    }

    @Test
    fun `short sentences are spoken whole`() {
        val sentence = "The castle was built in 1250, and it still stands today."
        assertEquals(listOf(sentence), SpeechChunker.chunks(sentence))
    }

    @Test
    fun `long sentences are cut at clause boundaries without losing words`() {
        val sentence = "After the great fire of 1421, which destroyed most of the wooden houses in the old town, " +
            "the council ordered that every new house be built of brick; many of those houses still line the canals."
        val chunks = SpeechChunker.chunks(sentence)
        assertTrue(chunks.size >= 2, "chunks: $chunks")
        assertEquals(sentence, chunks.joinToString(" "))
        assertTrue(chunks.all { it.split(' ').size >= SpeechChunker.MIN_WORDS }, "chunks: $chunks")
        assertTrue(chunks.dropLast(1).all { it.last() in ",;:—" }, "chunks: $chunks")
    }

    @Test
    fun `numbers with thousands separators and short tails stay together`() {
        val sentence = "The abbey owned more than 1,000 hectares of farmland and forest around the village by the year 1500, it is said."
        val chunks = SpeechChunker.chunks(sentence)
        assertEquals(listOf(sentence), chunks)
    }
}
