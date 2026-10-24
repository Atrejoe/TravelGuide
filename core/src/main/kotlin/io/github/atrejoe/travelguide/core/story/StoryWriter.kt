package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import kotlin.random.Random

/** A sentence from an article, with where it came from and how interesting it sounds. */
data class Candidate(val text: String, val kind: SectionKind, val order: Int, val score: Double)

/**
 * Turns article text into a short spoken story, without a language model: it prefers
 * anecdotes, origins of names, history and legends over statistics, keeps the original order so
 * the story flows, and adds a few spoken transitions. Works in the language of the place's
 * article (see [StoryLanguage]).
 */
class StoryWriter(private val random: Random = Random.Default) {

    fun write(poi: Poi, sections: List<ArticleSection>, maxSentences: Int): List<String> {
        val budget = maxSentences.coerceAtLeast(1)
        val language = StoryLanguages.of(poi.lang)
        // For towns and regions the short description is an administrative fact ("municipality in
        // Gelderland"); their history makes the better story.
        val identity = if (poi.type.isArea) null else identityLine(poi)
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
                lead(c.kind, language)?.let {
                    out += it
                    leads++
                }
            }
            out += c.text
            previousKind = c.kind
        }
        if (out.isEmpty()) {
            // Nothing scored well: a brief mention beats a long recital of dull facts.
            sections.firstOrNull()?.let { s -> out += SentenceSplitter.split(TextCleaner.clean(s.text), language).take(minOf(budget, 2)) }
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
        val language = StoryLanguages.of(poi.lang)
        var order = 0
        val result = mutableListOf<Candidate>()
        for (section in sections) {
            if (section.kind == SectionKind.SKIP) continue
            val sentences = SentenceSplitter.split(TextCleaner.clean(section.text), language)
            sentences.forEachIndexed { index, sentence ->
                val isDefinition = section.kind == SectionKind.INTRO && index == 0 && language.isDefinition(sentence, poi.title)
                if (!(skipDefinition && isDefinition)) {
                    val focus = if (poi.type.isArea) areaFocus(section.kind) else 1.0
                    result += Candidate(sentence, section.kind, order, score(sentence, section.kind, index == 0, language) * focus)
                }
                order++
            }
        }
        return result
    }

    /**
     * A short addition about a nearby place, woven into another story: its best one or two
     * sentences from the lead section.
     */
    fun companion(poi: Poi, maxSentences: Int = 2): List<String> {
        val intro = listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
        val picked = candidates(poi, intro, skipDefinition = false)
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(maxSentences)
            .sortedBy { it.order }
            .map { it.text }
        return picked.ifEmpty { SentenceSplitter.split(TextCleaner.clean(poi.extract), StoryLanguages.of(poi.lang)).take(1) }
    }

    /** For towns and regions, favour their past over today's facts and figures. */
    private fun areaFocus(kind: SectionKind): Double = when (kind) {
        SectionKind.HISTORY, SectionKind.NAME, SectionKind.LEGEND -> 1.15
        SectionKind.LANDMARKS -> 1.0
        else -> 0.7
    }

    /** "It is a village in Utrecht, Netherlands." from the Wikidata description, when usable. */
    internal fun identityLine(poi: Poi): String? {
        val description = poi.description?.let(TextCleaner::clean)?.trim()?.trimEnd('.') ?: return null
        if (description.length < 6 || "wikimedia" in description.lowercase() || "disambiguation" in description.lowercase()) {
            return null
        }
        return StoryLanguages.of(poi.lang).identityLine(description)
    }

    private fun lead(kind: SectionKind, language: StoryLanguage): String? {
        val options = language.leads(kind).ifEmpty { return null }
        // Not every change of topic needs an announcement.
        if (random.nextDouble() < 0.35) return null
        return options[random.nextInt(options.size)]
    }

    companion object {
        const val MIN_SCORE = 0.45
        const val MAX_PER_KIND = 3

        fun score(sentence: String, kind: SectionKind, firstInSection: Boolean, language: StoryLanguage = English): Double {
            if (kind == SectionKind.SKIP) return 0.0
            var s = 0.5
            if (language.remarkable.containsMatchIn(sentence)) s += 0.45
            if (language.nameOrigin.containsMatchIn(sentence)) s += if (kind == SectionKind.NAME) 0.5 else 0.2
            if (language.dated.containsMatchIn(sentence)) s += 0.2
            if (language.administrative.containsMatchIn(sentence)) s -= 0.8
            if (sentence.count { it.isDigit() } > 10) s -= 0.4
            if (sentence.count { it == ',' } > 5) s -= 0.3
            if (!firstInSection && language.contextDependent.containsMatchIn(sentence)) s -= 0.35
            when {
                sentence.length < 35 -> s -= 0.4
                sentence.length > 300 -> s -= 0.5
                sentence.length > 230 -> s -= 0.2
            }
            return s * kind.weight
        }
    }
}
