package io.github.atrejoe.travelguide.core.story

/**
 * What a building or institution is, with the words we use for it. [base] says how worth telling
 * such a place is by itself, before any facts are known.
 */
enum class PlaceKind(
    /** Spoken noun, e.g. "town hall". */
    val noun: String,
    val base: Double,
    val poiType: PoiType = PoiType.BUILDING,
    /** Its date is when the institution was founded rather than when a building went up. */
    val institution: Boolean = false,
    /** Old by nature, such as castles, gates and ruins. */
    val historic: Boolean = false,
    /** Made to look at rather than to enter: statues, monuments, fountains. */
    val isObject: Boolean = false,
) {
    TOWN_HALL("town hall", 0.85),
    CATHEDRAL("cathedral", 0.9),
    CHURCH("church", 0.7),
    CHAPEL("chapel", 0.6),
    MOSQUE("mosque", 0.7),
    SYNAGOGUE("synagogue", 0.75),
    TEMPLE("temple", 0.7),
    MONASTERY("monastery", 0.8, historic = true),
    MUSEUM("museum", 0.75, institution = true),
    GALLERY("art gallery", 0.5, institution = true),
    LIBRARY("library", 0.55, institution = true),
    UNIVERSITY("university building", 0.65, PoiType.EDUCATION, institution = true),
    COLLEGE("college", 0.5, PoiType.EDUCATION, institution = true),
    COURTHOUSE("courthouse", 0.6),
    THEATRE("theatre", 0.65, institution = true),
    CONCERT_HALL("concert hall", 0.65, institution = true),
    ARTS_CENTRE("arts centre", 0.5, institution = true),
    STATION("railway station", 0.55, PoiType.RAILWAY_STATION),
    CASTLE("castle", 0.95, PoiType.LANDMARK, historic = true),
    PALACE("palace", 0.9, historic = true),
    FORT("fortress", 0.85, PoiType.LANDMARK, historic = true),
    CITY_GATE("city gate", 0.85, PoiType.LANDMARK, historic = true),
    CITY_WALL("city wall", 0.8, PoiType.LANDMARK, historic = true),
    TOWER("tower", 0.7, PoiType.LANDMARK),
    WINDMILL("windmill", 0.8, PoiType.LANDMARK, historic = true),
    WATERMILL("watermill", 0.75, PoiType.LANDMARK, historic = true),
    LIGHTHOUSE("lighthouse", 0.75, PoiType.LANDMARK),
    MANOR("manor house", 0.75, historic = true),
    RUINS("ruin", 0.8, PoiType.LANDMARK, historic = true),
    ARCHAEOLOGICAL_SITE("archaeological site", 0.75, PoiType.LANDMARK, historic = true),
    MONUMENT("monument", 0.65, PoiType.LANDMARK, isObject = true),
    MEMORIAL("memorial", 0.55, PoiType.LANDMARK, isObject = true),
    STATUE("statue", 0.5, PoiType.LANDMARK, isObject = true),
    ARTWORK("work of art", 0.4, PoiType.LANDMARK, isObject = true),
    FOUNTAIN("fountain", 0.45, PoiType.LANDMARK, isObject = true),
    SHIP("historic ship", 0.6, PoiType.LANDMARK, historic = true),
    HISTORIC_BUILDING("historic building", 0.55, historic = true),
    BUILDING("building", 0.45),
    ATTRACTION("sight", 0.55, PoiType.LANDMARK),
    VIEWPOINT("viewpoint", 0.4, PoiType.LANDMARK),
}

/** A date as precise as the source knows it: a year, a decade or a century. */
data class HistoricDate(val year: Int, val precision: Precision) {
    enum class Precision { YEAR, DECADE, CENTURY }

    val century: Int get() = (year - 1) / 100 + 1

    /** "in 1488", "in the 1480s", "in the 15th century". */
    val inPhrase: String get() = if (precision == Precision.YEAR) "in $year" else "in $sincePhrase"

    /** "1488", "the 1480s", "the 15th century", to follow "since" or "dates from". */
    val sincePhrase: String
        get() = when (precision) {
            Precision.YEAR -> "$year"
            Precision.DECADE -> "the ${year / 10 * 10}s"
            Precision.CENTURY -> "the ${ordinal(century)} century"
        }

    companion object {
        private val fullDate = Regex("^~?(\\d{4})(-\\d{2}){0,2}$")
        private val range = Regex("^~?(\\d{4})\\s*\\.\\.\\s*\\d{4}$")
        private val decade = Regex("^~?(\\d{3})0s$")
        private val centuryTag = Regex("^~?C(\\d{1,2})$", RegexOption.IGNORE_CASE)
        private val wikidataTime = Regex("^\\+(\\d{1,4})-")

        /**
         * Reads OpenStreetMap's `start_date`: "1488", "1488-05-01", "~1600", "1480s", "C15" or
         * "1600..1650". Vaguer forms ("early 1500s", "before 1500") give null.
         */
        fun parseOsm(raw: String?, currentYear: Int): HistoricDate? {
            val text = raw?.trim() ?: return null
            val date = fullDate.matchEntire(text)?.let { HistoricDate(it.groupValues[1].toInt(), Precision.YEAR) }
                ?: range.matchEntire(text)?.let { HistoricDate(it.groupValues[1].toInt(), Precision.YEAR) }
                ?: decade.matchEntire(text)?.let { HistoricDate(it.groupValues[1].toInt() * 10, Precision.DECADE) }
                ?: centuryTag.matchEntire(text)?.let { HistoricDate((it.groupValues[1].toInt() - 1) * 100 + 50, Precision.CENTURY) }
            return date?.takeIf { it.year in 1..currentYear }
        }

        /** Reads a Wikidata time such as "+1488-00-00T00:00:00Z" with its precision (9 = year). */
        fun parseWikidata(time: String, precision: Int, currentYear: Int): HistoricDate? {
            val year = wikidataTime.find(time)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (year !in 1..currentYear) return null
            return when {
                precision >= 9 -> HistoricDate(year, Precision.YEAR)
                precision == 8 -> HistoricDate(year, Precision.DECADE)
                precision == 7 -> HistoricDate(if (year % 100 == 0) year - 50 else year, Precision.CENTURY)
                else -> null
            }
        }

        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }
    }
}

/**
 * What OpenStreetMap and Wikidata say about a building or institution, already in words that
 * can be spoken (no raw tag values).
 */
data class PlaceFacts(
    val kind: PlaceKind,
    /** Wikidata's short description, e.g. "weigh house in Amsterdam, Netherlands". */
    val description: String? = null,
    /** When it was built, founded or unveiled. */
    val since: HistoricDate? = null,
    val architects: List<String> = emptyList(),
    /** Architectural styles, e.g. "Gothic architecture" or "Art Nouveau". */
    val styles: List<String> = emptyList(),
    /** A noun phrase, e.g. "a Rijksmonument, a listed national monument". */
    val heritage: String? = null,
    val namedAfter: String? = null,
    /** Who uses it today, e.g. "Utrecht University". */
    val occupant: String? = null,
    /** An older name of the place. */
    val formerName: String? = null,
) {
    /** How many things we can tell beyond what kind of place it is. */
    val substance: Int
        get() = listOf(description, since, architects.firstOrNull(), styles.firstOrNull(), heritage, namedAfter, occupant, formerName)
            .count { it != null }

    /** True for places whose story is history: old by nature, protected or built before 1900. */
    val historic: Boolean get() = kind.historic || heritage != null || (since != null && since.year < 1900)

    /**
     * How worth telling the place is: its kind, raised by every fact we know. Kept below what a
     * good Wikipedia article reaches, which tells a better story; a linked article helps.
     */
    fun prominence(hasArticle: Boolean): Double =
        kind.base * (0.4 + 0.12 * substance).coerceAtMost(0.85) * (if (hasArticle) 1.25 else 1.0)
}
