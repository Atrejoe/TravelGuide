package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale

internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Something that can follow a short link (e.g. maps.app.goo.gl) to the URL it points at. */
fun interface LinkResolver {
    /** Returns the final URL after redirects, throwing on network errors. */
    suspend fun resolve(url: String): String
}

/** A place found by name. */
data class GeocodedPlace(val point: GeoPoint, val displayName: String?)

/**
 * Looks up place names with OpenStreetMap's Nominatim. Its usage policy asks for a descriptive
 * User-Agent (sent by the [HttpFetcher]) and at most one request per second, which is enforced
 * here for the whole process.
 */
class NominatimClient(
    private val http: HttpFetcher,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun search(query: String): GeocodedPlace? {
        val url = searchUrl(query)
        val body = throttle.withLock {
            val wait = lastRequestMillis + MIN_INTERVAL_MILLIS - clock()
            if (wait > 0) delay(wait)
            try {
                http.get(url)
            } finally {
                lastRequestMillis = clock()
            }
        }
        return parse(body)
    }

    fun searchUrl(query: String): String =
        "$baseUrl/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(query, "UTF-8")

    fun parse(body: String): GeocodedPlace? {
        val first = lenientJson.parseToJsonElement(body).let { it as? JsonArray }?.firstOrNull() as? JsonObject ?: return null
        val lat = first["lat"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
        val lon = first["lon"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeocodedPlace(GeoPoint(lat, lon), first["display_name"]?.jsonPrimitive?.contentOrNull)
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1_100L
        val throttle = Mutex()

        @Volatile
        var lastRequestMillis = 0L
    }
}

/** Which OSRM profile to route with. */
enum class OsrmProfile(val path: String) {
    CAR("routed-car"),
    BIKE("routed-bike"),
    FOOT("routed-foot"),
}

/** A route computed by OSRM. */
data class OsrmRoute(val points: List<GeoPoint>, val distanceM: Double, val durationS: Double)

/**
 * Computes routes with the free OSRM servers run by FOSSGIS (routing.openstreetmap.de), which
 * cover car, bike and foot profiles. No API key needed; please keep the request rate low.
 */
class OsrmClient(
    private val http: HttpFetcher,
    private val baseUrl: String = "https://routing.openstreetmap.de",
) {
    suspend fun route(stops: List<GeoPoint>, profile: OsrmProfile): OsrmRoute = parse(http.get(routeUrl(stops, profile)))

    fun routeUrl(stops: List<GeoPoint>, profile: OsrmProfile): String {
        require(stops.size >= 2) { "A route needs at least two stops" }
        val coordinates = stops.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lon, it.lat) }
        return "$baseUrl/${profile.path}/route/v1/driving/$coordinates?overview=full&geometries=polyline6"
    }

    /** Throws [RouteImportException] when OSRM found no route. */
    fun parse(body: String): OsrmRoute {
        val root = try {
            lenientJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw RouteImportException(RouteImportException.Reason.ROUTING_FAILED, "The routing service gave an unexpected answer.", e)
        }
        val code = root["code"]?.jsonPrimitive?.contentOrNull
        val route = root["routes"]?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
        if (code != "Ok" || route == null) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull
            throw RouteImportException(
                RouteImportException.Reason.ROUTING_FAILED,
                "No route found between these places" + (message?.let { " ($it)" } ?: "") + ".",
            )
        }
        val geometry = route["geometry"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val points = PolylineDecoder.decode(geometry, precision = 6)
        if (points.size < 2) throw RouteImportException(RouteImportException.Reason.ROUTING_FAILED, "The routing service returned an empty route.")
        return OsrmRoute(
            points = points,
            distanceM = route["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            durationS = route["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}

/** A Komoot tour's name and coordinates. */
data class KomootTourData(val name: String?, val points: List<GeoPoint>)

/**
 * Reads a public (or share-token) Komoot tour.
 *
 * **Unofficial:** this uses the endpoint Komoot's own web app uses
 * (`api.komoot.de/v007/tours/<id>/coordinates`). It is not a documented public API and may
 * change or disappear at any time; the GPX export is the fallback.
 */
class KomootClient(
    private val http: HttpFetcher,
    private val baseUrl: String = "https://api.komoot.de/v007",
) {
    suspend fun tour(id: String, shareToken: String?): KomootTourData {
        val points = parseCoordinates(http.get(coordinatesUrl(id, shareToken)))
        // The name is a nice-to-have; the route matters.
        val name = try {
            parseName(http.get(tourUrl(id, shareToken)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return KomootTourData(name, points)
    }

    fun coordinatesUrl(id: String, shareToken: String?): String = "$baseUrl/tours/$id/coordinates" + token(shareToken)

    fun tourUrl(id: String, shareToken: String?): String = "$baseUrl/tours/$id" + token(shareToken)

    private fun token(shareToken: String?) =
        shareToken?.takeIf { it.isNotBlank() }?.let { "?share_token=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()

    fun parseCoordinates(body: String): List<GeoPoint> {
        val items = lenientJson.parseToJsonElement(body).jsonObject["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = (o["lng"] ?: o["lon"])?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
        }
    }

    fun parseName(body: String): String? =
        lenientJson.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
