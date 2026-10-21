package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage

/** What a section of an article is about, which decides how story-worthy its sentences are. */
enum class SectionKind(val weight: Double) {
    INTRO(1.0),
    NAME(1.35),
    LEGEND(1.4),
    HISTORY(1.25),
    CULTURE(1.05),
    LANDMARKS(1.05),
    NATURE(1.05),
    CLIMATE(0.95),
    OTHER(0.6),
    /** Statistics, administration, references: never narrated. */
    SKIP(0.0),
    ;

    companion object {
        fun fromTitle(title: String, language: StoryLanguage = English): SectionKind {
            val t = title.lowercase()
            if (t.isBlank()) return INTRO
            return language.sectionRules.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: OTHER
        }
    }
}

data class ArticleSection(val title: String, val kind: SectionKind, val text: String)

object ArticleParser {
    private val heading = Regex("^(={2,6})\\s*(.*?)\\s*\\1\\s*$")

    /**
     * Splits a plain-text Wikipedia extract (`exsectionformat=wiki`) into sections. Subsections
     * inherit the kind of their parent unless their own title says otherwise, so "History >
     * Middle Ages" stays history while "History > Population" is skipped.
     */
    fun parse(extract: String, language: StoryLanguage = English): List<ArticleSection> {
        val sections = mutableListOf<ArticleSection>()
        var title = ""
        var kind = SectionKind.INTRO
        val parents = ArrayDeque<Pair<Int, SectionKind>>()
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) sections += ArticleSection(title, kind, text)
            buffer.clear()
        }

        for (line in extract.lines()) {
            val match = heading.matchEntire(line.trim())
            if (match == null) {
                buffer.append(line).append('\n')
                continue
            }
            flush()
            val level = match.groupValues[1].length
            title = match.groupValues[2]
            while (parents.isNotEmpty() && parents.last().first >= level) parents.removeLast()
            val own = SectionKind.fromTitle(title, language)
            val parent = parents.lastOrNull()?.second
            kind = when {
                parent == SectionKind.SKIP -> SectionKind.SKIP
                own == SectionKind.OTHER && parent != null -> parent
                else -> own
            }
            parents.addLast(level to kind)
        }
        flush()
        return sections
    }
}
