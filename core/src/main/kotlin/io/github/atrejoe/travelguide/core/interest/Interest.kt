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
        Interest.PLACES to listOf(
            "city", "town", "village", "municipality", "capital", "province", "region", "county",
            "district", "neighbourhood", "neighborhood",
        ),
    )

    fun classify(poi: Poi): Set<Interest> {
        val text = buildString {
            append(poi.description.orEmpty().lowercase()).append(' ')
            append(poi.extract.take(300).lowercase())
        }
        val found = keywords.filterValues { words -> words.any { it in text } }.keys.toMutableSet()
        when (poi.type) {
            PoiType.CITY, PoiType.REGION, PoiType.COUNTRY -> found += Interest.PLACES
            PoiType.MOUNTAIN, PoiType.RIVER, PoiType.WATERBODY, PoiType.GLACIER, PoiType.FOREST,
            PoiType.ISLE, PoiType.PASS -> found += Interest.NATURE
            PoiType.BUILDING, PoiType.LANDMARK -> found += Interest.ARCHITECTURE
            else -> Unit
        }
        return found
    }

    /** The strongest weight among the POI's interests (1.0 when none match). */
    fun weight(poi: Poi, weights: Map<Interest, Double>): Double {
        val tags = classify(poi)
        if (tags.isEmpty()) return 1.0
        return tags.maxOf { weights[it] ?: 1.0 }
    }
}
