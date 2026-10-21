package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind

/** How a building is protected, put into words by [StoryLanguage.heritage]. */
sealed interface Heritage {
    data object WorldHeritage : Heritage
    data object Rijksmonument : Heritage
    /** A British listing, e.g. "Grade II*". */
    data class ListedStatus(val status: String) : Heritage
    data object Listed : Heritage
    data object National : Heritage
}

/**
 * Everything the built-in storyteller reads and says in one language (issue #68): which article
 * sections and sentences make a good story, and the phrases a story is built from. Stories, the
 * Wikipedia edition and the voice all follow the chosen language; the app's own screens stay in
 * English.
 *
 * Lists of phrases are alternatives: one is picked at random for variety.
 */
abstract class StoryLanguage(
    /** ISO 639-1 code, also the Wikipedia edition: "en", "nl". */
    val code: String,
    /** The language's name in English, for settings and language-model prompts. */
    val name: String,
    /** The language's own name for itself, e.g. "Nederlands". */
    val nativeName: String,
) {
    // Reading articles

    /** Words in section titles that say what a section is about, checked in this order. */
    abstract val sectionRules: List<Pair<SectionKind, List<String>>>

    /** Abbreviations (lower case, without the final dot) after which a sentence goes on. */
    abstract val abbreviations: Set<String>

    /** Words that start a new sentence after a single letter, as in "Henry V. The tower". */
    abstract val sentenceStarters: Set<String>

    // Choosing sentences

    /** Superlatives, legends, disasters, famous people: what makes a sentence worth telling. */
    abstract val remarkable: Regex

    /** Where a name comes from. */
    abstract val nameOrigin: Regex

    /** Years and centuries. */
    abstract val dated: Regex

    /** Statistics and administration, which make dull listening. */
    abstract val administrative: Regex

    /** Openings that only make sense after the sentence before. */
    abstract val contextDependent: Regex

    /** True when [sentence], the first of an article about [title], merely defines it ("X is a town in …"). */
    abstract fun isDefinition(sentence: String, title: String): Boolean

    /** "It is a village in Utrecht, Netherlands." from a Wikidata short description; null when unusable. */
    abstract fun identityLine(description: String): String?

    /** Short spoken announcements of a new topic. */
    abstract fun leads(kind: SectionKind): List<String>

    // Interests

    /** Words in a description or lead that tag a place with an interest. */
    abstract val interestKeywords: Map<Interest, List<String>>

    /** First words of a description that make a place a town or region ("municipality in Gelderland"). */
    abstract val placeNouns: Set<String>

    /** Words that, among those first words, make it something else after all ("village church"). */
    abstract val notPlaceNouns: Set<String>

    // Telling where a place is

    abstract fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String>
    abstract fun aheadOpeners(title: String, distance: String): List<String>
    abstract fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String>
    abstract fun behindOpeners(title: String, distance: String): List<String>
    abstract fun compassOpeners(title: String, direction: String, distance: String): List<String>

    /** Openers for a place out of sight, further afield (issue #64). */
    abstract fun afieldOpeners(title: String, relative: RelativePosition): List<String>

    /** Leads from one story into a nearby place; [mentioned] when the first story's article names it. */
    abstract fun transitions(title: String, where: String, mentioned: Boolean): List<String>

    /** Closing remarks; empty for none. */
    abstract fun closings(relative: RelativePosition, mode: TravelMode): List<String>

    /** Spoken location, e.g. "on your left, about 800 metres away". */
    abstract fun where(relative: RelativePosition): String

    /** Spoken distance, e.g. "800 metres" or "2.5 kilometres". */
    abstract fun distance(meters: Double): String

    /** Names of the eight compass directions, from north clockwise. */
    abstract val compassNames: List<String>

    fun compassName(bearing: Double): String =
        compassNames[((GeoMath.normalizeBearing(bearing) + 22.5) / 45).toInt() % 8]

    // Places without an article (issue #35)

    /** What to call a kind of place, e.g. "town hall". */
    abstract fun placeNoun(kind: PlaceKind): String

    /** "Catholic church" from the OpenStreetMap denomination tag and a noun; just the noun for unknown ones. */
    abstract fun withDenomination(denomination: String?, noun: String): String

    abstract fun heritage(heritage: Heritage): String

    /** "It is the city hall of Utrecht." for a place a town has only one of; null when the description doesn't fit. */
    abstract fun theOne(kind: PlaceKind, description: String): String?
    abstract fun formerName(name: String): List<String>

    /** [ago] is empty or a phrase like ", more than 500 years ago". */
    abstract fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String>
    abstract fun ago(years: Int): String
    abstract fun architectLines(kind: PlaceKind, names: String): List<String>
    abstract fun styleLines(style: String): List<String>
    abstract fun heritageLines(heritage: String): List<String>
    abstract fun namedAfterLines(name: String): List<String>
    abstract fun occupantLines(name: String): List<String>
    abstract fun joinNames(names: List<String>): String
}

object StoryLanguages {
    val all: List<StoryLanguage> = listOf(English, Dutch)

    /** The language with [code], or English when there is none. */
    fun of(code: String?): StoryLanguage = all.firstOrNull { it.code == code } ?: English
}
