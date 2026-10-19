package io.github.atrejoe.travelguide.core.wiki

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.util.Locale

/** Minimal HTTP abstraction so the core stays platform independent and testable. */
fun interface HttpFetcher {
    /** Performs a GET and returns the response body, throwing on network or HTTP errors. */
    suspend fun get(url: String): String
}

/** Something that can find places near a point. */
fun interface PoiSource {
    suspend fun nearby(center: GeoPoint, radiusM: Int): List<Poi>
}

/** Something that can provide the full article about a place, for richer stories. */
fun interface ArticleSource {
    /** Plain text with `== Section ==` headings, or null when unavailable. */
    suspend fun article(poi: Poi): String?
}

/**
 * Finds geotagged Wikipedia articles near a point, together with their lead section, in a single
 * request (geosearch generator + extracts). Free to use; please keep request rates modest and send
 * a descriptive User-Agent from the [HttpFetcher] as the Wikimedia API etiquette asks.
 */
class WikipediaClient(
    private val http: HttpFetcher,
    private val lang: String = "en",
    private val limit: Int = 20,
) : PoiSource, ArticleSource {

    override suspend fun nearby(center: GeoPoint, radiusM: Int): List<Poi> =
        parse(http.get(nearbyUrl(center, radiusM)))

    /**
     * The full article: by page id for geosearch results, by title for places found elsewhere
     * (see [Poi.wikiTitle]); null when the place has neither.
     */
    override suspend fun article(poi: Poi): String? = when {
        poi.isWikipediaPage -> parseArticle(http.get(articleUrl(poi.pageId)))
        poi.wikiTitle != null -> parseArticle(http.get(articleUrl(title = poi.wikiTitle)))
        else -> null
    }

    /**
     * The article [title] as a place with its lead section, like a geosearch result; null when
     * missing. A page without coordinates is placed at [fallback], or skipped without one.
     */
    suspend fun page(title: String, fallback: GeoPoint? = null): Poi? =
        parse(http.get(pageUrl(title)), fallback).firstOrNull()

    fun articleUrl(pageId: Long? = null, title: String? = null): String = apiUrl(
        linkedMapOf(
            "action" to "query",
            "format" to "json",
            "formatversion" to "2",
            "prop" to "extracts",
            "explaintext" to "1",
            "exsectionformat" to "wiki",
        ) + if (pageId != null) mapOf("pageids" to pageId.toString()) else mapOf("titles" to title.orEmpty(), "redirects" to "1"),
    )

    fun pageUrl(title: String): String = apiUrl(
        linkedMapOf("action" to "query", "format" to "json", "formatversion" to "2", "titles" to title, "redirects" to "1") +
            pageProps(),
    )

    /** What we ask for every page: coordinates, short description, lead section, URL, Wikidata id. */
    private fun pageProps(): Map<String, String> = linkedMapOf(
        "prop" to "coordinates|description|extracts|info|pageprops",
        "coprop" to "type|dim",
        "colimit" to "max",
        "exintro" to "1",
        "explaintext" to "1",
        "exlimit" to "20",
        "inprop" to "url",
        "ppprop" to "wikibase_item",
    )

    fun parseArticle(body: String): String? =
        json.decodeFromString(ApiResponse.serializer(), body).query?.pages?.firstOrNull()?.extract?.takeIf { it.isNotBlank() }

    fun nearbyUrl(center: GeoPoint, radiusM: Int): String {
        val params = linkedMapOf(
            "action" to "query",
            "format" to "json",
            "formatversion" to "2",
            "generator" to "geosearch",
            "ggscoord" to String.format(Locale.ROOT, "%.6f|%.6f", center.lat, center.lon),
            "ggsradius" to radiusM.coerceIn(10, 10_000).toString(),
            "ggslimit" to limit.coerceIn(1, 20).toString(),
            "ggsnamespace" to "0",
        )
        return apiUrl(params + pageProps())
    }

    private fun apiUrl(params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "https://$lang.wikipedia.org/w/api.php?$query"
    }

    /** Pages with coordinates; those without are placed at [fallback], or left out without one. */
    fun parse(body: String, fallback: GeoPoint? = null): List<Poi> {
        val response = json.decodeFromString(ApiResponse.serializer(), body)
        return response.query?.pages.orEmpty().mapNotNull { page ->
            if (page.missing == true || page.pageid <= 0) return@mapNotNull null
            val coordinate = page.coordinates?.firstOrNull()
            val point = coordinate?.let { GeoPoint(it.lat, it.lon) } ?: fallback ?: return@mapNotNull null
            Poi(
                pageId = page.pageid,
                title = page.title,
                point = point,
                type = PoiType.parse(coordinate?.type),
                population = PoiType.parsePopulation(coordinate?.type),
                dimM = coordinate?.dim?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() },
                description = page.description,
                extract = page.extract.orEmpty(),
                url = page.fullurl ?: "https://$lang.wikipedia.org/?curid=${page.pageid}",
                lang = lang,
                wikidataId = page.pageprops?.wikibaseItem,
            )
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

@Serializable
internal data class ApiResponse(val query: ApiQuery? = null)

@Serializable
internal data class ApiQuery(val pages: List<ApiPage> = emptyList())

@Serializable
internal data class ApiPage(
    val pageid: Long = 0,
    val title: String = "",
    val missing: Boolean? = null,
    val coordinates: List<ApiCoordinate>? = null,
    val description: String? = null,
    val extract: String? = null,
    val fullurl: String? = null,
    val pageprops: ApiPageProps? = null,
)

@Serializable
internal data class ApiPageProps(@SerialName("wikibase_item") val wikibaseItem: String? = null)

@Serializable
internal data class ApiCoordinate(
    val lat: Double,
    val lon: Double,
    val type: String? = null,
    val dim: JsonPrimitive? = null,
)
