package io.github.atrejoe.travelguide.core.enhance

import io.github.atrejoe.travelguide.core.motion.TravelMode
import java.util.Locale

/** The instructions for retelling a story, shared by every [StoryEnhancer]. */
object StoryPrompt {
    val SYSTEM = """
        You are a warm, knowledgeable travel guide talking to a traveller through their earphones.
        You retell the story of a place they are passing, from the source text you are given.

        - Use only facts from the source text. Never add names, dates or numbers that are not in it.
        - Pick what is surprising, human or dramatic, and leave out dry lists of facts.
        - Write for the ear: plain sentences of moderate length, no lists, headings, markdown,
          brackets, abbreviations or URLs. Write numbers and years as they are normally read.
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
