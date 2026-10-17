package io.github.atrejoe.travelguide.core.story

/** Makes encyclopedic text pleasant to listen to. */
object TextCleaner {
    private val whitespace = Regex("\\s+")
    private val spaceBeforePunctuation = Regex("\\s+([,.;:!?])")
    private val doublePunctuation = Regex("([,;:])\\s*([,.;:])")

    fun clean(text: String): String {
        val withoutBrackets = removeBracketed(text)
        return withoutBrackets
            .replace(whitespace, " ")
            .replace(spaceBeforePunctuation, "$1")
            .replace(doublePunctuation, "$2")
            .trim()
    }

    /**
     * Drops everything in (parentheses) and [brackets]: pronunciations, alternative names, dates
     * and citation marks read badly aloud and are rarely missed.
     */
    private fun removeBracketed(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (c in text) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth-- else out.append(c)
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.toString()
    }
}

object SentenceSplitter {
    private val abbreviations = setOf(
        "st", "mt", "mr", "mrs", "ms", "dr", "prof", "jr", "sr", "vs", "c", "ca", "approx", "no",
        "e.g", "i.e", "etc", "u.s", "u.k", "a.d", "b.c", "inc", "ltd", "co", "ft", "km", "fig",
    )
    private val boundary = Regex("(?<=[.!?])[\"”’)]?\\s+(?=[\"“‘(]?[A-Z0-9À-Ý])")

    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var last = 0
        for (match in boundary.findAll(text)) {
            current.append(text, last, match.range.first)
            last = match.range.last + 1
            val lastWord = current.trimEnd().split(' ').last().trimEnd('.', '!', '?').lowercase()
            val isAbbreviation = lastWord in abbreviations || (lastWord.length == 1 && lastWord[0].isLetter())
            if (isAbbreviation) {
                current.append(' ')
            } else {
                sentences += current.toString().trim()
                current.clear()
            }
        }
        current.append(text.substring(last))
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { sentences += it }
        return sentences
    }
}
