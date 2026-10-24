package io.github.atrejoe.travelguide.core.enhance

import io.github.atrejoe.travelguide.core.motion.TravelMode
import java.util.Locale

/** The instructions for retelling a story, shared by every [StoryEnhancer]. */
object StoryPrompt {
    val SYSTEM = """
        You are a gifted storyteller guiding a traveller through their earphones. You tell them the
        story of a place they are passing, from the source text you are given, the way a good guide
        would over a drink: one story, not a summary of an encyclopedia article.

        - Find the story in the source: an event, a mystery, a legend, a person, a turning point.
          If the source tells of something that happened, tell that.
        - Open with a hook that makes the traveller want to hear more, build up to what happened,
          and end on a payoff: an outcome, a twist or a curious detail that lingers.
        - Leave out what a place is administratively, where it lies in which province or
          department, population figures and other dry facts, unless they matter to the story.
        - Use only facts from the source text. Never add names, dates or numbers that are not in it.
        - Write for the ear: plain sentences of moderate length that follow on from each other, no
          lists, headings, markdown, brackets, abbreviations or URLs. Write numbers and years as
          they are normally read.
        - Do not greet the traveller, do not repeat the opening sentence, and do not mention
          Wikipedia, the source text or yourself.
        - Answer with the story only.
    """.trimIndent()

    fun user(request: StoryRequest): String {
        val travelling = when (request.mode) {
            TravelMode.WALKING -> "walking, so they can take a good look"
            TravelMode.CYCLING -> "cycling past"
            TravelMode.DRIVING -> "driving past, eyes on the road"
            TravelMode.TRAIN -> "looking out of a train window"
            TravelMode.FLYING -> "flying over, high above"
            TravelMode.STATIONARY -> "standing still"
        }
        val language = Locale.forLanguageTag(request.lang).getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
        return buildString {
            appendLine("Place: ${request.title}")
            request.description?.let { appendLine("What it is: $it") }
            appendLine("Where: ${request.where}")
            appendLine("The traveller is $travelling.")
            if (request.interests.isNotEmpty()) {
                appendLine("They are most interested in: ${request.interests.joinToString { it.name.lowercase() }}.")
            }
            appendLine("The guide has just said: \"${request.opener}\"")
            appendLine()
            appendLine("Source text:")
            appendLine("<source>")
            appendLine(request.source)
            appendLine("</source>")
            appendLine()
            append("Continue with the story of this place in about ${request.sentences} sentences, in $language.")
        }
    }

    /**
     * A story cut off at the output limit, up to its last complete sentence; null when not even
     * one sentence is complete.
     */
    fun completeSentences(text: String): String? {
        val end = text.indexOfLast { it == '.' || it == '!' || it == '?' }
        return if (end < 0) null else text.substring(0, end + 1).trim().ifEmpty { null }
    }
}
