package io.github.atrejoe.travelguide.core.wiki

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiType
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

    override suspend fun article(poi: Poi): String? = parseArticle(http.get(articleUrl(poi.pageId)))

    fun articleUrl(pageId: Long): String = apiUrl(
        linkedMapOf(
            "action" to "query",
            "format" to "json",
            "formatversion" to "2",
            "prop" to "extracts",
            "explaintext" to "1",
            "exsectionformat" to "wiki",
            "pageids" to pageId.toString(),
        ),
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
            "prop" to "coordinates|description|extracts|info",
            "coprop" to "type|dim",
            "colimit" to "max",
            "exintro" to "1",
            "explaintext" to "1",
            "exlimit" to "20",
            "inprop" to "url",
        )
        return apiUrl(params)
    }

    private fun apiUrl(params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "https://$lang.wikipedia.org/w/api.php?$query"
    }

    fun parse(body: String): List<Poi> {
        val response = json.decodeFromString(ApiResponse.serializer(), body)
        return response.query?.pages.orEmpty().mapNotNull { page ->
            val coordinate = page.coordinates?.firstOrNull() ?: return@mapNotNull null
            if (page.missing == true) return@mapNotNull null
            Poi(
                pageId = page.pageid,
                title = page.title,
                point = GeoPoint(coordinate.lat, coordinate.lon),
                type = PoiType.parse(coordinate.type),
                population = PoiType.parsePopulation(coordinate.type),
                dimM = coordinate.dim?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() },
                description = page.description,
                extract = page.extract.orEmpty(),
                url = page.fullurl ?: "https://$lang.wikipedia.org/?curid=${page.pageid}",
                lang = lang,
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
)

@Serializable
internal data class ApiCoordinate(
    val lat: Double,
    val lon: Double,
    val type: String? = null,
    val dim: JsonPrimitive? = null,
)
