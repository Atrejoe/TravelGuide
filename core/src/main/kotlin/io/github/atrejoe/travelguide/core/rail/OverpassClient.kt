package io.github.atrejoe.travelguide.core.rail

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.road.RoadNetwork
import io.github.atrejoe.travelguide.core.road.RoadSource
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale

/** A tagged OpenStreetMap object at a point (the centre of a way or relation). */
data class OsmFeature(
    /** "node", "way" or "relation". */
    val type: String,
    val id: Long,
    val point: GeoPoint,
    val tags: Map<String, String>,
) {
    val url: String get() = "https://www.openstreetmap.org/$type/$id"
}

/**
 * Reads railway lines and roads from OpenStreetMap through the public Overpass API (free, no
 * key), and named buildings and institutions for walking tours. Queries are small and only
 * repeated after moving away from the area fetched before.
 */
class OverpassClient(
    private val http: HttpFetcher,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
    private val clock: () -> Long = System::currentTimeMillis,
) : RailSource, RoadSource {

    override suspend fun around(center: GeoPoint, radiusM: Int): RailNetwork =
        parse(http.get(url(center, radiusM)), center, radiusM)

    override suspend fun roads(center: GeoPoint, majorRadiusM: Int, minorRadiusM: Int, cycleways: Boolean): RoadNetwork =
        parseRoads(http.get(roadsUrl(center, majorRadiusM, minorRadiusM, cycleways)), center, majorRadiusM, minorRadiusM, cycleways)

    fun url(center: GeoPoint, radiusM: Int): String {
        val around = aroundFilter(center, radiusM)
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

    /**
     * Main roads within [majorRadiusM]; when [minorRadiusM] > 0 also smaller roads (and, with
     * [cycleways], cycle paths) within that smaller radius, which keeps the answer small.
     */
    fun roadsUrl(center: GeoPoint, majorRadiusM: Int, minorRadiusM: Int, cycleways: Boolean): String {
        val major = aroundFilter(center, majorRadiusM)
        val minor = aroundFilter(center, minorRadiusM)
        val lines = buildList {
            add("""way($major)["highway"~"^(motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link)$"];""")
            if (minorRadiusM > 0) {
                add("""way($minor)["highway"~"^(tertiary|tertiary_link|unclassified|residential|living_street)$"];""")
                if (cycleways) add("""way($minor)["highway"="cycleway"];""")
            }
        }
        val query = "[out:json][timeout:25];\n(\n" + lines.joinToString("\n") + "\n);\nout geom;"
        return "$endpoint?data=${URLEncoder.encode(query, "UTF-8")}"
    }

    /** Named buildings, institutions and monuments within [radiusM]; see [placesUrl]. */
    suspend fun places(center: GeoPoint, radiusM: Int): List<OsmFeature> = parsePlaces(http.get(placesUrl(center, radiusM)))

    /**
     * Named places worth a story: town halls, churches, museums, libraries, universities,
     * courthouses, theatres, stations, castles, mills, monuments, listed buildings, and named
     * buildings with a Wikidata link or a construction date. Stumbling stones and plaques are
     * left out (a busy old town has hundreds). Ways and relations come with their centre.
     */
    fun placesUrl(center: GeoPoint, radiusM: Int): String {
        val a = aroundFilter(center, radiusM)
        val query = """
            [out:json][timeout:25];
            (
              nwr($a)["name"]["amenity"~"^(townhall|place_of_worship|library|university|college|courthouse|theatre|concert_hall|arts_centre|monastery|fountain)$"];
              nwr($a)["name"]["tourism"~"^(museum|gallery|artwork|attraction|viewpoint)$"];
              nwr($a)["name"]["historic"]["memorial"!~"^(stolperstein|stumbling_stone|plaque|blue_plaque|ghost_bike)$"];
              nwr($a)["name"]["heritage"];
              nwr($a)["name"]["man_made"~"^(windmill|watermill|lighthouse|tower)$"];
              nwr($a)["name"]["railway"="station"]["station"!~"^(subway|light_rail|monorail)$"];
              wr($a)["name"]["building"]["wikidata"];
              wr($a)["name"]["building"]["start_date"];
            );
            out tags center qt $PLACES_LIMIT;
        """.trimIndent()
        return "$endpoint?data=${URLEncoder.encode(query, "UTF-8")}"
    }

    fun parsePlaces(body: String): List<OsmFeature> =
        json.decodeFromString(Response.serializer(), body).elements.mapNotNull { e ->
            val point = when {
                e.lat != null && e.lon != null -> GeoPoint(e.lat, e.lon)
                e.center != null -> GeoPoint(e.center.lat, e.center.lon)
                else -> return@mapNotNull null
            }
            if (e.type !in setOf("node", "way", "relation")) return@mapNotNull null
            OsmFeature(e.type, e.id, point, e.tags)
        }

    private fun aroundFilter(center: GeoPoint, radiusM: Int) =
        String.format(Locale.ROOT, "around:%d,%.6f,%.6f", radiusM, center.lat, center.lon)

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

    fun parseRoads(body: String, center: GeoPoint, majorRadiusM: Int, minorRadiusM: Int, cycleways: Boolean): RoadNetwork {
        val ways = json.decodeFromString(Response.serializer(), body).elements
            .filter { it.type == "way" && it.geometry.size >= 2 && it.tags.containsKey("highway") }
            .map { e -> OsmWay(e.id, e.nodes, e.geometry.map { GeoPoint(it.lat, it.lon) }, e.tags.filterKeys { it in ROAD_TAGS }) }
        return RoadNetwork(center, majorRadiusM.toDouble(), minorRadiusM.toDouble(), ways, clock(), cycleways)
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
        val lat: Double? = null,
        val lon: Double? = null,
        val center: LatLon? = null,
    )

    @Serializable
    private data class LatLon(val lat: Double, val lon: Double)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Enough for the centre of an old town within the radius we ask for. */
        const val PLACES_LIMIT = 400

        /** The tags road following uses; the rest is dropped to save memory. */
        val ROAD_TAGS = setOf("highway", "name", "ref", "oneway", "oneway:bicycle", "junction", "destination", "destination:ref")
    }
}
