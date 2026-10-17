package io.github.atrejoe.travelguide.core.story

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
        private val rules: List<Pair<SectionKind, List<String>>> = listOf(
            SKIP to listOf(
                "demograph", "population", "government", "politic", "administrat", "economy",
                "transport", "education", "sport", "twin", "sister cit", "reference", "notes",
                "further reading", "external link", "see also", "gallery", "bibliograph", "sources",
                "notable people", "people", "infrastructure", "media", "health", "statistic",
                "subdivision", "neighbourhood", "neighborhood", "district", "list of", "citations",
            ),
            NAME to listOf("etymolog", "name", "toponym"),
            LEGEND to listOf("legend", "folklore", "myth", "ghost", "mystery"),
            HISTORY to listOf("history", "origin", "founding", "medieval", "middle ages", "war", "ancient", "prehistor", "century"),
            CULTURE to listOf("culture", "art", "festival", "cuisine", "food", "music", "religio", "tradition", "event"),
            LANDMARKS to listOf("landmark", "architecture", "sight", "monument", "building", "attraction", "heritage"),
            NATURE to listOf("geograph", "geolog", "nature", "landscape", "flora", "fauna", "wildlife", "ecolog", "environment", "course", "hydrolog"),
            CLIMATE to listOf("climate", "weather"),
        )

        fun fromTitle(title: String): SectionKind {
            val t = title.lowercase()
            if (t.isBlank()) return INTRO
            return rules.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: OTHER
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
    fun parse(extract: String): List<ArticleSection> {
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
            val own = SectionKind.fromTitle(title)
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
