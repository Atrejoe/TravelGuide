package io.github.atrejoe.travelguide.core.places

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.nav.NominatimClient
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import io.github.atrejoe.travelguide.core.wiki.WikidataClient
import io.github.atrejoe.travelguide.core.wiki.WikipediaClient

/**
 * Finds the town we are in and its Wikipedia article: Nominatim says which town (and its
 * Wikidata item), Wikidata gives the article title in our language, Wikipedia the article.
 * Answers are cached per town, so walking around in one town costs one Nominatim request per
 * check and nothing more.
 */
class TownLookup(
    private val nominatim: NominatimClient,
    private val wikidata: WikidataClient,
    private val wikipedia: WikipediaClient,
    private val lang: String = "en",
) : TownSource {
    private val cache = HashMap<String, Poi?>()

    override suspend fun town(point: GeoPoint): Poi? {
        val place = nominatim.reverse(point) ?: return null
        val key = place.wikidata ?: place.wikipedia ?: return null
        if (cache.containsKey(key)) return cache[key]
        val title = place.wikipedia?.takeIf { it.startsWith("$lang:") }?.substringAfter(':')
            ?: place.wikidata?.let { wikidata.entities(listOf(it), claims = false)[it]?.sitelink }
        val town = title?.let { wikipedia.page(it, fallback = point) }?.let { page ->
            page.copy(
                // We know it is a town, whatever the article's coordinates say.
                type = if (page.type.isArea) page.type else PoiType.CITY,
                wikidataId = page.wikidataId ?: place.wikidata,
            )
        }
        cache[key] = town
        return town
    }
}
