package io.github.atrejoe.travelguide.core.interest

import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType

/** Topics the user can weigh up or down. */
enum class Interest {
    HISTORY,
    NATURE,
    CULTURE,
    ARCHITECTURE,
    PLACES,
}

/** Keyword-based tagging; deliberately cheap so no language model is needed at runtime. */
object InterestClassifier {
    private val keywords: Map<Interest, List<String>> = mapOf(
        Interest.HISTORY to listOf(
            "battle", "castle", "fort", "war", "ancient", "medieval", "roman", "century", "historic",
            "ruin", "monument", "memorial", "archaeolog", "abbey", "palace", "founded", "siege",
            "monastery", "manor", "estate", "dynasty", "viking", "prehistoric", "burial", "mound",
            "fortress", "rampart", "citadel", "heritage", "former", "demolished", "rebuilt",
        ),
        Interest.NATURE to listOf(
            "river", "lake", "mountain", "forest", "nature reserve", "national park", "valley", "hill",
            "island", "glacier", "dune", "heath", "wetland", "bird", "species", "climate", "geolog",
        ),
        Interest.CULTURE to listOf(
            "museum", "festival", "theatre", "theater", "art", "music", "painter", "writer", "cuisine",
            "tradition", "religio", "church", "cathedral", "mosque", "temple", "university",
        ),
        Interest.ARCHITECTURE to listOf(
            "building", "tower", "bridge", "architect", "windmill", "mill", "house", "castle", "church",
            "cathedral", "station", "canal", "lock", "dam", "skyscraper",
        ),
    )

    /** Description openings that mark an administrative place ("municipality in Gelderland"). */
    private val placeNouns = setOf(
        "city", "town", "village", "municipality", "capital", "province", "region", "county",
        "district", "neighbourhood", "neighborhood", "hamlet", "commune", "borough", "parish",
        "former", "human", "place", "census-designated",
    )

    fun classify(poi: Poi): Set<Interest> {
        val text = buildString {
            append(poi.description.orEmpty().lowercase()).append(' ')
            append(poi.extract.take(300).lowercase())
        }
        val found = keywords.filterValues { words -> words.any { it in text } }.keys.toMutableSet()
        // Only what a place *is* makes it a town or region, not a mention of "the region" in its text.
        val firstWords = poi.description.orEmpty().lowercase().split(' ', ',').filter { it.isNotBlank() }.take(3)
        if (firstWords.firstOrNull() in placeNouns && firstWords.none { it in setOf("church", "castle", "fort", "mill") }) {
            found += Interest.PLACES
        }
        when (poi.type) {
            PoiType.CITY, PoiType.REGION, PoiType.COUNTRY -> found += Interest.PLACES
            PoiType.MOUNTAIN, PoiType.RIVER, PoiType.WATERBODY, PoiType.GLACIER, PoiType.FOREST,
            PoiType.ISLE, PoiType.PASS -> found += Interest.NATURE
            PoiType.BUILDING, PoiType.LANDMARK -> found += Interest.ARCHITECTURE
            else -> Unit
        }
        return found
    }

    /**
     * The strongest weight among the POI's topical interests (1.0 when none match), scaled by the
     * [Interest.PLACES] weight for towns and regions: a historic town still ranks well, a plain
     * municipality does not.
     */
    fun weight(poi: Poi, weights: Map<Interest, Double>): Double {
        val tags = classify(poi)
        val topical = (tags - Interest.PLACES).maxOfOrNull { weights[it] ?: 1.0 } ?: 1.0
        val places = if (Interest.PLACES in tags) weights[Interest.PLACES] ?: 1.0 else 1.0
        return topical * places
    }

    /**
     * The default emphasis: the history of the surroundings first, administrative units last.
     * Users will be able to change this (issue #4).
     */
    val defaultWeights: Map<Interest, Double> = mapOf(
        Interest.HISTORY to 1.6,
        Interest.ARCHITECTURE to 1.1,
        Interest.PLACES to 0.55,
    )
}
