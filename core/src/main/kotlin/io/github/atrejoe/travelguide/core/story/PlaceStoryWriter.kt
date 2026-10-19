package io.github.atrejoe.travelguide.core.story

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

    /** Up to [maxSentences] sentences about [poi]; empty when it has no facts. */
    fun write(poi: Poi, maxSentences: Int): List<String> {
        val facts = poi.facts ?: return emptyList()
        val out = mutableListOf<String>()
        (theOne(poi, facts) ?: identity.identityLine(poi))?.let { out += it }
        facts.formerName?.takeIf { !it.equals(poi.title, ignoreCase = true) }?.let {
            out += pick("It was once known as $it.", "In the past it was called $it.")
        }
        facts.since?.let { out += date(facts.kind, it) }
        facts.architects.takeIf { it.isNotEmpty() }?.let { out += architect(facts.kind, joinNames(it.take(2))) }
        facts.styles.firstOrNull()?.let { out += style(it) }
        facts.heritage?.let { out += pick("It is $it.", "Today it is protected as $it.") }
        facts.namedAfter?.let { out += pick("It is named after ${withThe(it)}.", "Its name honours ${withThe(it)}.") }
        facts.occupant?.takeIf { !it.equals(poi.title, ignoreCase = true) }?.let {
            out += pick("Today it is home to ${withThe(it)}.", "These days it houses ${withThe(it)}.")
        }
        return out.take(maxSentences.coerceAtLeast(1))
    }

    /**
     * "It is the city hall of Utrecht, Netherlands." for a place a town has only one of, where
     * "a city hall of Utrecht" would sound wrong.
     */
    private fun theOne(poi: Poi, facts: PlaceFacts): String? {
        if (facts.kind !in uniqueKinds) return null
        val description = poi.description?.trim()?.trimEnd('.') ?: return null
        if (" of " !in description.substringBefore(',') || description.startsWith("the ", ignoreCase = true)) return null
        return "It is the ${description.replaceFirstChar { it.lowercase() }}."
    }

    private fun date(kind: PlaceKind, date: HistoricDate): String {
        val age = currentYear - date.year
        val ago = if (age >= 100 && random.nextDouble() < 0.7) ", more than ${roundedAge(age)} years ago" else ""
        return when {
            kind.institution -> pick("It was founded ${date.inPhrase}$ago.", "It was established ${date.inPhrase}$ago.")
            kind.isObject -> pick("It was placed here ${date.inPhrase}$ago.", "It has stood here since ${date.sincePhrase}.")
            else -> pick(
                "It was built ${date.inPhrase}$ago.",
                "It dates from ${date.sincePhrase}.",
                "It has stood here since ${date.sincePhrase}.",
            )
        }
    }

    private fun architect(kind: PlaceKind, names: String): String = when {
        kind.isObject -> pick("It is the work of $names.", "It was made by $names.")
        kind.institution -> pick("Its building was designed by $names.", "The architect of its building was $names.")
        else -> pick("It was designed by $names.", "The architect was $names.")
    }

    private fun style(label: String): String {
        val clean = label.trim()
        if (clean.endsWith(" architecture", ignoreCase = true)) {
            val base = clean.dropLast(" architecture".length)
            return pick("It is a fine example of $base architecture.", "Notice its $base architecture.")
        }
        return pick("Its style is known as $clean.", "It is built in a style known as $clean.")
    }

    private fun pick(vararg options: String): String = options[random.nextInt(options.size)]

    companion object {
        private val uniqueKinds = setOf(PlaceKind.TOWN_HALL, PlaceKind.CATHEDRAL, PlaceKind.COURTHOUSE, PlaceKind.CITY_WALL)

        /** 538 years -> 500, 160 -> 150, 120 -> 100: round numbers sound natural. */
        fun roundedAge(age: Int): Int = if (age >= 200) age / 100 * 100 else age / 50 * 50

        fun joinNames(names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }

        /** "municipal council" -> "the municipal council"; proper names stay as they are. */
        fun withThe(label: String): String =
            if (label.firstOrNull()?.isLowerCase() == true && !label.startsWith("the ")) "the $label" else label
    }
}
