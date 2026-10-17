package io.github.atrejoe.travelguide.core.rail

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale

/**
 * Reads railway lines and major roads from OpenStreetMap through the public Overpass API
 * (free, no key). Queries are small and only repeated after moving a few kilometres.
 */
class OverpassClient(
    private val http: HttpFetcher,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
    private val clock: () -> Long = System::currentTimeMillis,
) : RailSource {

    override suspend fun around(center: GeoPoint, radiusM: Int): RailNetwork =
        parse(http.get(url(center, radiusM)), center, radiusM)

    fun url(center: GeoPoint, radiusM: Int): String {
        val around = String.format(Locale.ROOT, "around:%d,%.6f,%.6f", radiusM, center.lat, center.lon)
        val query = """
            [out:json][timeout:25];
            (
              way($around)["railway"~"^(rail|light_rail|narrow_gauge|subway|monorail)$"];
              way($around)["highway"~"^(motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link)$"];
            );
            out geom;
        """.trimIndent()
        return "$endpoint?data=${URLEncoder.encode(query, "UTF-8")}"
    }

    fun parse(body: String, center: GeoPoint, radiusM: Int): RailNetwork {
        val elements = json.decodeFromString(Response.serializer(), body).elements
        val rails = mutableListOf<OsmWay>()
        val roads = mutableListOf<OsmWay>()
        for (e in elements) {
            if (e.type != "way" || e.geometry.size < 2) continue
            val way = OsmWay(e.id, e.nodes, e.geometry.map { GeoPoint(it.lat, it.lon) })
            when {
                e.tags.containsKey("railway") -> rails += way
                e.tags.containsKey("highway") -> roads += way
            }
        }
        return RailNetwork(center, radiusM.toDouble(), rails, roads, clock())
    }

    @Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @Serializable
    private data class Element(
        val type: String = "",
        val id: Long = 0,
        val nodes: List<Long> = emptyList(),
        val geometry: List<LatLon> = emptyList(),
        val tags: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class LatLon(val lat: Double, val lon: Double)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
