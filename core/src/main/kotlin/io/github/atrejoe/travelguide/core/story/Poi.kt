package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.geo.GeoPoint

/**
 * Kind of place, taken from the `type:` parameter of Wikipedia's coordinate templates.
 * [prominence] says how worth mentioning such a place is when nothing else is known.
 */
enum class PoiType(val prominence: Double, val isArea: Boolean = false) {
    COUNTRY(1.0, isArea = true),
    REGION(0.95, isArea = true),
    CITY(0.85, isArea = true),
    ISLE(0.8, isArea = true),
    MOUNTAIN(0.85),
    PASS(0.6),
    RIVER(0.8),
    WATERBODY(0.8, isArea = true),
    GLACIER(0.85),
    FOREST(0.7, isArea = true),
    LANDMARK(0.7),
    BUILDING(0.5),
    RAILWAY_STATION(0.35),
    AIRPORT(0.55),
    EDUCATION(0.4),
    EVENT(0.6),
    UNKNOWN(0.5),
    ;

    companion object {
        /** Parses a coordinate type such as `city(120000)` or `adm2nd`. */
        fun parse(raw: String?): PoiType {
            val base = raw?.substringBefore('(')?.trim()?.lowercase() ?: return UNKNOWN
            return when (base) {
                "country", "satellite" -> COUNTRY
                "state", "adm1st", "adm2nd", "adm3rd" -> REGION
                "city" -> CITY
                "isle" -> ISLE
                "mountain" -> MOUNTAIN
                "pass" -> PASS
                "river" -> RIVER
                "waterbody" -> WATERBODY
                "glacier" -> GLACIER
                "forest" -> FOREST
                "landmark" -> LANDMARK
                "building" -> BUILDING
                "railwaystation" -> RAILWAY_STATION
                "airport" -> AIRPORT
                "edu" -> EDUCATION
                "event" -> EVENT
                else -> UNKNOWN
            }
        }

        /** Extracts the population from a type such as `city(120000)`, if present. */
        fun parsePopulation(raw: String?): Long? =
            raw?.substringAfter('(', "")?.substringBefore(')')?.replace(",", "")?.trim()?.toLongOrNull()
    }
}

/** A place we could tell the user about, with its encyclopedic summary. */
data class Poi(
    val pageId: Long,
    val title: String,
    val point: GeoPoint,
    val type: PoiType = PoiType.UNKNOWN,
    val population: Long? = null,
    /** Approximate size of the object in metres, from the coordinate `dim:` parameter. */
    val dimM: Double? = null,
    /** Wikidata short description, e.g. "city in Utrecht, Netherlands". */
    val description: String? = null,
    /** Plain-text lead section of the article. */
    val extract: String = "",
    val url: String = "",
    val lang: String = "en",
)
