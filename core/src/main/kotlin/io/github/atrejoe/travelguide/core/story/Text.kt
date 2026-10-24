package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage

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
    private val boundary = Regex("(?<=[.!?])[\"”’)]?\\s+(?=[\"“‘(]?[A-Z0-9À-Ý])")

    fun split(text: String, language: StoryLanguage = English): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var last = 0
        for (match in boundary.findAll(text)) {
            current.append(text, last, match.range.first)
            last = match.range.last + 1
            val lastWord = current.trimEnd().split(' ').last().trimEnd('.', '!', '?').lowercase()
            val nextWord = text.substring(last).takeWhile { it.isLetterOrDigit() }
            // "J. van Campen" is an initial, but in "Henry V. The tower" a new sentence starts.
            val isInitial = lastWord.length == 1 && lastWord[0].isLetter() && nextWord !in language.sentenceStarters
            val isAbbreviation = lastWord in language.abbreviations || isInitial
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

/**
 * Cuts long sentences at clause boundaries for on-device voices, which take longer to render a
 * long sentence than to play a short one before it. Sentences up to [MAX_WORDS] words stay whole
 * so most keep their natural intonation, and no piece is shorter than [MIN_WORDS] words.
 */
object SpeechChunker {
    const val MAX_WORDS = 16
    const val MIN_WORDS = 6

    private val clauseEnd = Regex("(?<=[,;:—])\\s+")
    private val space = Regex("\\s+")

    fun chunks(sentence: String): List<String> {
        val text = sentence.trim()
        if (words(text) <= MAX_WORDS) return listOf(text)
        val out = mutableListOf<String>()
        var current = ""
        for (clause in text.split(clauseEnd)) {
            if (current.isEmpty()) {
                current = clause
            } else if (words(current) >= MIN_WORDS && words(current) + words(clause) > MAX_WORDS) {
                out += current
                current = clause
            } else {
                current = "$current $clause"
            }
        }
        // A short tail sounds clipped on its own; keep it with the clause before it.
        if (out.isNotEmpty() && words(current) < MIN_WORDS) out[out.lastIndex] = "${out.last()} $current" else out += current
        return out
    }

    private fun words(text: String): Int = text.split(space).count { it.isNotEmpty() }
}
