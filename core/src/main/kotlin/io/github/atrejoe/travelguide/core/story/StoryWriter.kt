package io.github.atrejoe.travelguide.core.story

import kotlin.random.Random

/** A sentence from an article, with where it came from and how interesting it sounds. */
data class Candidate(val text: String, val kind: SectionKind, val order: Int, val score: Double)

/**
 * Turns article text into a short spoken story, without a language model: it prefers
 * anecdotes, origins of names, history and legends over statistics, keeps the original order so
 * the story flows, and adds a few spoken transitions.
 */
class StoryWriter(private val random: Random = Random.Default) {

    fun write(poi: Poi, sections: List<ArticleSection>, maxSentences: Int): List<String> {
        val budget = maxSentences.coerceAtLeast(1)
        val identity = identityLine(poi)
        val candidates = candidates(poi, sections, skipDefinition = identity != null)
        val chosen = select(candidates, if (identity != null) budget - 1 else budget)
            // The name's origin makes a natural start; otherwise keep the article's order.
            .sortedWith(compareBy<Candidate>({ it.kind != SectionKind.NAME }, { it.order }))

        val out = mutableListOf<String>()
        identity?.let { out += it }
        var leads = 0
        var previousKind: SectionKind? = null
        for (c in chosen) {
            if (c.kind != previousKind && leads < 2 && out.isNotEmpty()) {
                lead(c.kind)?.let {
                    out += it
                    leads++
                }
            }
            out += c.text
            previousKind = c.kind
        }
        if (out.isEmpty()) {
            // Nothing scored well: fall back to the opening of the article.
            sections.firstOrNull()?.let { s -> out += SentenceSplitter.split(TextCleaner.clean(s.text)).take(budget) }
        }
        return out
    }

    /**
     * Best sentences first, but always room for where the name comes from, and no more than
     * [MAX_PER_KIND] from one topic so a long history section doesn't crowd out everything else.
     */
    private fun select(candidates: List<Candidate>, count: Int): List<Candidate> {
        val good = candidates.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }
        val picked = mutableListOf<Candidate>()
        good.firstOrNull { it.kind == SectionKind.NAME }?.let { picked += it }
        for (c in good) {
            if (picked.size >= count) break
            if (c in picked || picked.count { it.kind == c.kind } >= MAX_PER_KIND) continue
            picked += c
        }
        return picked.take(count)
    }

    internal fun candidates(poi: Poi, sections: List<ArticleSection>, skipDefinition: Boolean): List<Candidate> {
        var order = 0
        val result = mutableListOf<Candidate>()
        for (section in sections) {
            if (section.kind == SectionKind.SKIP) continue
            val sentences = SentenceSplitter.split(TextCleaner.clean(section.text))
            sentences.forEachIndexed { index, sentence ->
                val isDefinition = section.kind == SectionKind.INTRO && index == 0 && isDefinition(sentence, poi.title)
                if (!(skipDefinition && isDefinition)) {
                    result += Candidate(sentence, section.kind, order, score(sentence, section.kind, index == 0))
                }
                order++
            }
        }
        return result
    }

    /** "It is a village in Utrecht, Netherlands." from the Wikidata description, when usable. */
    internal fun identityLine(poi: Poi): String? {
        val description = poi.description?.let(TextCleaner::clean)?.trim()?.trimEnd('.') ?: return null
        if (description.length < 6 || "wikimedia" in description.lowercase() || "disambiguation" in description.lowercase()) {
            return null
        }
        val lowered = description.replaceFirstChar { it.lowercase() }
        val phrase = when {
            lowered.startsWith("the ") -> lowered
            // Keep proper adjectives capitalised ("a Dutch village", "a Roman fort"), but not
            // ordinary words that a short description happens to start with.
            looksLikeProperAdjective(description.substringBefore(' ')) -> description
            else -> lowered
        }
        val article = when {
            phrase.startsWith("the ") -> ""
            phrase.first().lowercaseChar() in "aeiou" -> "an "
            else -> "a "
        }
        return "It is $article$phrase."
    }

    private fun looksLikeProperAdjective(word: String): Boolean {
        if (word.isEmpty() || !word.first().isUpperCase()) return false
        val lower = word.lowercase()
        if (lower in commonNouns) return false
        return demonymSuffixes.any { lower.endsWith(it) }
    }

    private fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        return sentence.length < 220 &&
            (head.startsWith(title.lowercase()) || head.startsWith("the " + title.lowercase())) &&
            definitionVerbs.any { it in head }
    }

    private fun lead(kind: SectionKind): String? {
        val options = when (kind) {
            SectionKind.NAME -> listOf("Ever wondered about the name?", "The name has a story of its own.")
            SectionKind.LEGEND -> listOf("There is a legend, too.", "And then there is the story people tell.")
            SectionKind.HISTORY -> listOf("A little history.", "Let's go back in time.", "History has left its mark here.")
            SectionKind.CULTURE -> listOf("Culture runs deep here.", "Something about local life.")
            SectionKind.LANDMARKS -> listOf("Look closely and you'll notice this.", "A detail worth knowing.")
            SectionKind.NATURE -> listOf("Now, about the landscape.", "Nature has its own story here.")
            SectionKind.CLIMATE -> listOf("As for the weather here.")
            else -> return null
        }
        // Not every change of topic needs an announcement.
        if (random.nextDouble() < 0.35) return null
        return options[random.nextInt(options.size)]
    }

    companion object {
        const val MIN_SCORE = 0.45
        const val MAX_PER_KIND = 3

        private val commonNouns = setOf(
            "city", "town", "village", "municipality", "river", "lake", "mountain", "hill", "church",
            "castle", "building", "museum", "station", "capital", "former", "historic", "national",
            "island", "bridge", "tower", "park", "district", "region", "province", "street", "square",
            "neighbourhood", "hamlet", "place", "area", "forest", "canal", "church", "branch", "beach",
            "arch", "urban", "suburban", "human", "public", "civic", "historic", "scenic", "gothic",
        )
        private val demonymSuffixes = listOf("ish", "ese", "ian", "an", "ch", "ic", "i")
        private val definitionVerbs = listOf(" is a", " is an", " is the", " was a", " was an", " was the", " are a", " is one")

        private val remarkable = Regex(
            "\\b(oldest|largest|biggest|tallest|longest|highest|deepest|smallest|first|only|last|famous|" +
                "renowned|remarkable|unique|legend|legendary|myth|according to|nicknamed|known as|" +
                "destroyed|burned|flood|plague|siege|battle|besieged|treaty|discovered|invented|" +
                "survived|miracle|secret|mystery|king|queen|emperor|roman|romans|viking|medieval|" +
                "monks?|knights?|saint|pilgrim|treasure|ruins?)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val nameOrigin = Regex(
            "\\b(named after|derived from|derives from|comes from|the name|meaning|means)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val dated = Regex("\\b(1[0-9]{3}|20[0-2][0-9]|[0-9]{1,2}(st|nd|rd|th) century|BC|AD)\\b")
        private val administrative = Regex(
            "\\b(population|inhabitants|census|municipality of|km2|km²|square kilomet|square mile|" +
                "postal code|postcode|area code|density|per cent|percent|households|median|" +
                "administrative|seat of|statistics|elected|council|mayor|coordinates|" +
                "is located in the province|lies in the province|consists of the)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val contextDependent = Regex("^(It|This|These|They|He|She|His|Her|Its|However|But|Also|Both|There)\\b")

        fun score(sentence: String, kind: SectionKind, firstInSection: Boolean): Double {
            if (kind == SectionKind.SKIP) return 0.0
            var s = 0.5
            if (remarkable.containsMatchIn(sentence)) s += 0.45
            if (nameOrigin.containsMatchIn(sentence)) s += if (kind == SectionKind.NAME) 0.5 else 0.2
            if (dated.containsMatchIn(sentence)) s += 0.2
            if (administrative.containsMatchIn(sentence)) s -= 0.8
            if (sentence.count { it.isDigit() } > 10) s -= 0.4
            if (sentence.count { it == ',' } > 5) s -= 0.3
            if (!firstInSection && contextDependent.containsMatchIn(sentence)) s -= 0.35
            when {
                sentence.length < 35 -> s -= 0.4
                sentence.length > 300 -> s -= 0.5
                sentence.length > 230 -> s -= 0.2
            }
            return s * kind.weight
        }
    }
}
