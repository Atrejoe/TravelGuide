package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import kotlin.random.Random

/**
 * A sentence from an article, with where it came from and how interesting it sounds. [section]
 * counts runs of sections about one topic, so a story can go on into a subsection; [opens] is
 * true where a story can start: at a paragraph, or a sentence that doesn't lean on the one before.
 */
data class Candidate(
    val text: String,
    val kind: SectionKind,
    val order: Int,
    val score: Double,
    val section: Int = 0,
    val opens: Boolean = true,
    /** For an event: part of the section that tells what happened. */
    val main: Boolean = false,
)

/**
 * Turns article text into a short spoken story, without a language model. It tells one or two
 * whole passages of the article, in their own order, rather than the best sentences from all over
 * it: sentences picked one by one lose what they refer to and sound like a garbled summary. The
 * passages are those richest in anecdotes, origins of names, history, legends and dramatic events;
 * statistics and administration are left out. Works in the language of the place's article (see
 * [StoryLanguage]).
 */
class StoryWriter(private val random: Random = Random.Default) {

    fun write(poi: Poi, sections: List<ArticleSection>, maxSentences: Int): List<String> {
        val budget = maxSentences.coerceAtLeast(1)
        val language = StoryLanguages.of(poi.lang)
        // For towns and regions the short description is an administrative fact ("municipality in
        // Gelderland"); their history makes the better story. An event's article opens with what
        // happened, which says more than "It is a mass poisoning in France".
        val identity = if (poi.type.isArea || isEvent(poi)) null else identityLine(poi)
        val candidates = candidates(poi, sections, skipDefinition = identity != null)
        val passages = passages(candidates, if (identity != null) budget - 1 else budget)

        val out = mutableListOf<String>()
        identity?.let { out += it }
        var leads = 0
        var previousKind: SectionKind? = null
        for (passage in passages) {
            val kind = passage.first().kind
            if (kind != previousKind && leads < 2 && out.isNotEmpty()) {
                lead(kind, language)?.let {
                    out += it
                    leads++
                }
            }
            out += passage.map { it.text }
            previousKind = kind
        }
        if (passages.isEmpty()) {
            // Nothing reads like a story: a brief mention beats a long recital of dull facts.
            val first = SentenceSplitter.split(TextCleaner.clean(sections.firstOrNull()?.text.orEmpty()), language)
            out += first.filter { score(it, SectionKind.INTRO, true, language) >= DULL }.ifEmpty { first }.take(minOf(budget, 2))
        }
        return out
    }

    /**
     * The passages to tell, at most [count] sentences in all: where the name comes from when the
     * article says, then the best run of sentences, then perhaps another one. Each passage keeps
     * the article's order, and the passages are told in the article's order.
     */
    internal fun passages(candidates: List<Candidate>, count: Int): List<List<Candidate>> {
        val chosen = mutableListOf<List<Candidate>>()
        val taken = mutableSetOf<Int>()
        var left = count
        fun take(run: List<Candidate>?) {
            if (run == null || left <= 0) return
            chosen += run
            taken += run.map { it.order }
            left -= run.size
        }
        // The origin of a name makes a natural start.
        take(bestRun(candidates.filter { it.kind == SectionKind.NAME }, taken, minOf(left, 2), minGain = 0.0))
        // An event is told as it happened: how the article sums it up, then its account of it.
        if (candidates.any { it.main }) {
            take(bestRun(candidates.filter { it.kind == SectionKind.INTRO }, taken, minOf(left, 2), minGain = 0.0))
            take(bestRun(candidates.filter { it.main }, taken, left, minGain = 0.0))
        }
        val most = if (count <= 5) 2 else 3
        while (left > 0 && chosen.size < most) {
            val run = bestRun(candidates, taken, left, minGain = if (chosen.isEmpty()) 0.0 else SECOND_GAIN) ?: break
            take(run)
        }
        return chosen
            .map { run -> run.filter { it.score >= DULL || it === run.first() } }
            .sortedWith(compareBy<List<Candidate>>({ it.first().kind != SectionKind.NAME }, { it.first().order }))
    }

    /**
     * The run of consecutive sentences within one topic, at most [max] long and starting where a
     * story can start, whose sentences are most worth telling together: each adds its score above
     * [MIN_SCORE], so a run grows through good sentences and stops at dull ones. Null when no run
     * gains more than [minGain].
     */
    internal fun bestRun(candidates: List<Candidate>, taken: Set<Int>, max: Int, minGain: Double): List<Candidate>? {
        if (max <= 0) return null
        var best: List<Candidate>? = null
        var bestGain = minGain
        for ((start, first) in candidates.withIndex()) {
            if (!first.opens || first.order in taken) continue
            var gain = 0.0
            var end = start
            while (end < candidates.size && end - start < max) {
                val c = candidates[end]
                if (c.order in taken || c.section != first.section || c.order != first.order + (end - start)) break
                gain += c.score - MIN_SCORE
                if (gain > bestGain) {
                    bestGain = gain
                    best = candidates.subList(start, end + 1).toList()
                }
                end++
            }
        }
        return best
    }

    internal fun candidates(poi: Poi, sections: List<ArticleSection>, skipDefinition: Boolean): List<Candidate> {
        val language = StoryLanguages.of(poi.lang)
        val event = isEvent(poi)
        var order = 0
        var topic = 0
        var previousKind: SectionKind? = null
        val result = mutableListOf<Candidate>()
        var mainTopic: Int? = null
        for ((index, section) in sections.withIndex()) {
            // A subsection carries on its section's story; a new topic starts a new one.
            if (section.kind != previousKind || index == 0) topic++
            previousKind = section.kind
            if (section.kind == SectionKind.SKIP) {
                order++ // Keeps what comes after from joining what came before.
                continue
            }
            if (section.kind != SectionKind.INTRO && mainTopic == null) mainTopic = topic
            val focus = when {
                event -> eventFocus(section.kind, main = topic == mainTopic)
                poi.type.isArea -> areaFocus(section.kind)
                else -> 1.0
            }
            val paragraphs = section.text.split('\n').map(TextCleaner::clean).filter { it.isNotEmpty() }
            for ((p, paragraph) in paragraphs.withIndex()) {
                var opening = true
                SentenceSplitter.split(paragraph, language).forEachIndexed { i, sentence ->
                    val first = p == 0 && i == 0
                    val isDefinition = section.kind == SectionKind.INTRO && first && language.isDefinition(sentence, poi.title)
                    if (skipDefinition && isDefinition) {
                        order++
                        return@forEachIndexed
                    }
                    val opens = opening || !language.contextDependent.containsMatchIn(sentence)
                    val score = score(sentence, section.kind, first, language) * focus
                    result += Candidate(sentence, section.kind, order, score, topic, opens, main = event && topic == mainTopic)
                    opening = false
                    order++
                }
            }
        }
        return result
    }

    /**
     * The story-worthy part of the article for a language model to retell (issue #78): the lead,
     * then the sections with a passage worth telling in the article's order, then the others, so
     * what is cut off at the model's limit matters least. Statistics, references and the like are
     * left out.
     */
    fun source(poi: Poi, sections: List<ArticleSection>): String {
        fun value(section: ArticleSection): Double =
            bestRun(candidates(poi, listOf(section), skipDefinition = false), emptySet(), 6, minGain = Double.NEGATIVE_INFINITY)
                ?.sumOf { it.score - MIN_SCORE } ?: -1.0
        val (intro, rest) = sections.filter { it.kind != SectionKind.SKIP }.partition { it.kind == SectionKind.INTRO }
        val (told, others) = rest.partition { value(it) > 0 }
        return (intro + told + others).joinToString("\n\n") { section ->
            val text = TextCleaner.clean(section.text)
            if (section.title.isBlank()) text else "${section.title}:\n$text"
        }
    }

    /**
     * A short addition about a nearby place, woven into another story: the best one or two
     * sentences in a row from the lead section.
     */
    fun companion(poi: Poi, maxSentences: Int = 2): List<String> {
        val intro = listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
        val run = bestRun(candidates(poi, intro, skipDefinition = false), emptySet(), maxSentences, minGain = 0.0)
        return run?.map { it.text } ?: SentenceSplitter.split(TextCleaner.clean(poi.extract), StoryLanguages.of(poi.lang)).take(1)
    }

    /** For towns and regions, favour their past over today's facts and figures. */
    private fun areaFocus(kind: SectionKind): Double = when (kind) {
        SectionKind.HISTORY, SectionKind.NAME, SectionKind.LEGEND -> 1.15
        SectionKind.LANDMARKS -> 1.0
        else -> 0.7
    }

    /**
     * An event's article is its story, whatever its sections are called ("Investigation",
     * "Aftermath"); the first section after the lead usually tells what happened, the heart of it.
     */
    private fun eventFocus(kind: SectionKind, main: Boolean): Double = when (kind) {
        SectionKind.INTRO -> 1.15
        else -> EVENT_SECTION_WEIGHT / kind.weight * if (main) 1.3 else 1.0
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

        /** Sentences below this are statistics or administration, dropped even from a good passage. */
        const val DULL = 0.2

        /** What every section of an event's article weighs, as much as history does. */
        private const val EVENT_SECTION_WEIGHT = 1.2

        /** How much a second or third passage must add to be told too. */
        const val SECOND_GAIN = 0.4

        private val leadValues = object : LinkedHashMap<String, Double>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>) = size > 500
        }

        /**
         * How story-like a place's lead section is, from 0.75 for one that only says where it lies
         * and how many live there, up to 1.2 for one full of events and legends. Places are ranked
         * by it before their full article is read (issue #78).
         */
        fun leadValue(poi: Poi): Double {
            if (poi.extract.isBlank()) return 1.0
            val key = "${poi.lang}:${poi.pageId}:${poi.title}"
            synchronized(leadValues) { leadValues[key] }?.let { return it }
            val writer = StoryWriter(Random(0))
            val intro = listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
            val candidates = writer.candidates(poi, intro, skipDefinition = false)
            val gain = writer.bestRun(candidates, emptySet(), LEAD_RUN, minGain = Double.NEGATIVE_INFINITY)
                ?.sumOf { it.score - MIN_SCORE } ?: -1.0
            val value = (0.85 + gain * 0.25).coerceIn(0.75, 1.2)
            synchronized(leadValues) { leadValues[key] = value }
            return value
        }

        private const val LEAD_RUN = 4

        /**
         * True for an article about something that happened, such as a poisoning, a battle or a
         * shipwreck (by its coordinates' type, or its title or description): the best stories.
         */
        fun isEvent(poi: Poi): Boolean {
            if (poi.type == PoiType.EVENT) return true
            if (poi.type.isArea) return false
            val dramatic = StoryLanguages.of(poi.lang).dramatic
            return dramatic.containsMatchIn(poi.title) || poi.description?.let(dramatic::containsMatchIn) == true
        }

        fun score(sentence: String, kind: SectionKind, firstInSection: Boolean, language: StoryLanguage = English): Double {
            if (kind == SectionKind.SKIP) return 0.0
            var s = 0.5
            if (language.remarkable.containsMatchIn(sentence)) s += 0.45
            if (language.dramatic.containsMatchIn(sentence)) s += 0.35
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
