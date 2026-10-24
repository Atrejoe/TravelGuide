package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import java.time.Year
import kotlin.random.Random

/**
 * Tells about a building or institution that has no Wikipedia article, from the facts
 * OpenStreetMap and Wikidata give: what it is, then its history (when it was built and by whom,
 * its style, its protection), then what it is today. No language model: variety comes from
 * phrasing pools.
 */
class PlaceStoryWriter(
    private val random: Random = Random.Default,
    private val currentYear: Int = Year.now().value,
) {
    private val identity = StoryWriter(random)

    /** Up to [maxSentences] sentences about [poi], in its language; empty when it has no facts. */
    fun write(poi: Poi, maxSentences: Int): List<String> {
        val facts = poi.facts ?: return emptyList()
        val language = StoryLanguages.of(poi.lang)
        val out = mutableListOf<String>()
        (theOne(poi, facts, language) ?: identity.identityLine(poi))?.let { out += it }
        facts.formerName?.takeIf { !it.equals(poi.title, ignoreCase = true) }?.let { out += pick(language.formerName(it)) }
        facts.since?.let { out += date(facts.kind, it, language) }
        facts.architects.takeIf { it.isNotEmpty() }?.let { out += pick(language.architectLines(facts.kind, language.joinNames(it.take(2)))) }
        facts.styles.firstOrNull()?.let { out += pick(language.styleLines(it.trim())) }
        facts.heritage?.let { out += pick(language.heritageLines(it)) }
        facts.namedAfter?.let { out += pick(language.namedAfterLines(it)) }
        facts.occupant?.takeIf { !it.equals(poi.title, ignoreCase = true) }?.let { out += pick(language.occupantLines(it)) }
        return out.take(maxSentences.coerceAtLeast(1))
    }

    /**
     * "It is the city hall of Utrecht, Netherlands." for a place a town has only one of, where
     * "a city hall of Utrecht" would sound wrong.
     */
    private fun theOne(poi: Poi, facts: PlaceFacts, language: StoryLanguage): String? {
        if (facts.kind !in uniqueKinds) return null
        val description = poi.description?.trim()?.trimEnd('.') ?: return null
        return language.theOne(facts.kind, description)
    }

    private fun date(kind: PlaceKind, date: HistoricDate, language: StoryLanguage): String {
        val age = currentYear - date.year
        val ago = if (age >= 100 && random.nextDouble() < 0.7) language.ago(roundedAge(age)) else ""
        return pick(language.dateLines(kind, date, ago))
    }

    private fun pick(options: List<String>): String = options[random.nextInt(options.size)]

    companion object {
        private val uniqueKinds = setOf(PlaceKind.TOWN_HALL, PlaceKind.CATHEDRAL, PlaceKind.COURTHOUSE, PlaceKind.CITY_WALL)

        /** 538 years -> 500, 160 -> 150, 120 -> 100: round numbers sound natural. */
        fun roundedAge(age: Int): Int = if (age >= 200) age / 100 * 100 else age / 50 * 50
    }
}
