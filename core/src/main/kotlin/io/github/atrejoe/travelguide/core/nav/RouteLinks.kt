package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import java.net.URLDecoder

/** A stop on a Google Maps route: a coordinate, a place name to look up, or "your location". */
sealed interface Waypoint {
    data class Coordinate(val point: GeoPoint) : Waypoint
    data class Place(val query: String) : Waypoint
    data object CurrentLocation : Waypoint
}

/** How Google Maps was asked to travel. */
enum class GoogleTravelMode { DRIVING, WALKING, BICYCLING, TRANSIT, TWO_WHEELER }

/** What a shared text or link points at. */
sealed interface RouteLink {
    /** The URL the classification is based on. */
    val url: String

    data class GoogleMapsDirections(
        override val url: String,
        val origin: Waypoint,
        val destination: Waypoint,
        val waypoints: List<Waypoint> = emptyList(),
        val travelMode: GoogleTravelMode? = null,
    ) : RouteLink {
        val stops: List<Waypoint> get() = listOf(origin) + waypoints + destination
    }

    /** A Google Maps link that is not a route, e.g. a single place. */
    data class GoogleMapsOther(override val url: String) : RouteLink

    /** A short link (maps.app.goo.gl, goo.gl/maps) that must be resolved first. */
    data class ShortLink(override val url: String) : RouteLink

    data class KomootTour(override val url: String, val id: String, val shareToken: String?) : RouteLink

    /** Komoot's generated suggestions; they have no public coordinates. */
    data class KomootSmartTour(override val url: String, val id: String) : RouteLink

    data class GarminCourse(override val url: String, val id: String?) : RouteLink

    data class Unknown(override val url: String) : RouteLink
}

/**
 * Recognises navigation links in shared text, such as "Directions to Utrecht https://maps.app.goo.gl/…"
 * or "Check out this tour on komoot: https://www.komoot.com/tour/123?share_token=…".
 */
object RouteLinks {

    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val URL_PARTS = Regex("""^https?://([^/?#]+)([^?#]*)(?:\?([^#]*))?(?:#.*)?$""", RegexOption.IGNORE_CASE)
    private val COORDINATE = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val GOOGLE_HOST = Regex("""^(?:www\.|maps\.)?google\.(?:[a-z]{2,3}|co\.[a-z]{2}|com\.[a-z]{2})$""")
    private val SHORT_HOSTS = setOf("goo.gl", "g.co")
    private val KOMOOT_HOST = Regex("""^(?:www\.|m\.)?komoot\.(?:com|de|nl|fr|it|es)$""")
    private val KOMOOT_TOUR = Regex("""/tour/(\d+)""")
    private val KOMOOT_SMART_TOUR = Regex("""/smarttour/([A-Za-z0-9]+)""")
    private val GARMIN_COURSE = Regex("""/course/(\d+)""")
    private val TRAVEL_MODE_IN_DATA = Regex("""!3e(\d)""")

    /** All http(s) URLs in [text], trailing punctuation removed. */
    fun extractUrls(text: String): List<String> =
        URL_PATTERN.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?') }.toList()

    /** Classifies the first useful link in [text]. Returns [RouteLink.Unknown] when nothing matches. */
    fun classify(text: String): RouteLink {
        val links = extractUrls(text).map(::classifyUrl)
        return links.firstOrNull { it !is RouteLink.Unknown } ?: links.firstOrNull() ?: RouteLink.Unknown(text.trim())
    }

    fun classifyUrl(url: String): RouteLink {
        // A lenient split instead of java.net.URI: shared links often contain unescaped characters.
        val match = URL_PARTS.matchEntire(url.trim()) ?: return RouteLink.Unknown(url)
        val host = match.groupValues[1].substringAfterLast('@').substringBefore(':').lowercase()
        val path = match.groupValues[2]
        val query = parseQuery(match.groupValues[3])
        return when {
            host == "maps.app.goo.gl" -> RouteLink.ShortLink(url)
            host in SHORT_HOSTS && path.startsWith("/maps") -> RouteLink.ShortLink(url)
            host == "consent.google.com" -> query["continue"]?.let(::classifyUrl) ?: RouteLink.Unknown(url)
            GOOGLE_HOST.matches(host) -> google(url, path, query)
            KOMOOT_HOST.matches(host) -> komoot(url, path, query)
            host.endsWith("garmin.com") -> garmin(url, host, path)
            else -> RouteLink.Unknown(url)
        }
    }

    private fun google(url: String, path: String, query: Map<String, String>): RouteLink {
        if (!path.startsWith("/maps")) return RouteLink.Unknown(url)
        // https://www.google.com/maps/dir/?api=1&origin=…&destination=…&waypoints=a|b&travelmode=…
        if (query["api"] == "1" && path.startsWith("/maps/dir")) {
            val destination = query["destination"]?.let(::waypoint) ?: return RouteLink.GoogleMapsOther(url)
            return RouteLink.GoogleMapsDirections(
                url = url,
                origin = query["origin"]?.let(::waypoint) ?: Waypoint.CurrentLocation,
                destination = destination,
                waypoints = query["waypoints"].orEmpty().split('|').filter { it.isNotBlank() }.map(::waypoint),
                travelMode = travelMode(query["travelmode"]),
            )
        }
        // Legacy https://maps.google.com/maps?saddr=A&daddr=B+to:C&dirflg=w
        query["daddr"]?.let { daddr ->
            val stops = daddr.split(Regex("""\s*\+?to:\s*""")).filter { it.isNotBlank() }.map(::waypoint)
            if (stops.isEmpty()) return RouteLink.GoogleMapsOther(url)
            return RouteLink.GoogleMapsDirections(
                url = url,
                origin = query["saddr"]?.takeIf { it.isNotBlank() }?.let(::waypoint) ?: Waypoint.CurrentLocation,
                destination = stops.last(),
                waypoints = stops.dropLast(1),
                travelMode = when (query["dirflg"]) {
                    "w" -> GoogleTravelMode.WALKING
                    "b" -> GoogleTravelMode.BICYCLING
                    "r" -> GoogleTravelMode.TRANSIT
                    "d" -> GoogleTravelMode.DRIVING
                    else -> null
                },
            )
        }
        // https://www.google.com/maps/dir/A/B/C/@52.1,5.1,12z/data=!4m2!4m1!3e1
        val segments = path.removePrefix("/").split('/')
        val dirIndex = segments.indexOf("dir")
        if (dirIndex < 0) return RouteLink.GoogleMapsOther(url)
        val stopSegments = mutableListOf<String>()
        var data: String? = null
        for (segment in segments.drop(dirIndex + 1)) {
            when {
                segment.startsWith("@") -> Unit // The map viewport, not a stop.
                segment.startsWith("data=") -> data = segment
                segment.startsWith("am=") -> Unit
                else -> stopSegments += segment
            }
        }
        // A trailing slash leaves an empty last segment; an empty first segment means "your location".
        while (stopSegments.isNotEmpty() && stopSegments.last().isEmpty()) stopSegments.removeAt(stopSegments.lastIndex)
        if (stopSegments.isEmpty()) return RouteLink.GoogleMapsOther(url)
        val stops = stopSegments.map { segment ->
            val decoded = decode(segment)
            if (decoded.isBlank()) Waypoint.CurrentLocation else waypoint(decoded)
        }
        val mode = data?.let { TRAVEL_MODE_IN_DATA.find(it)?.groupValues?.get(1) }?.let {
            when (it) {
                "0" -> GoogleTravelMode.DRIVING
                "1" -> GoogleTravelMode.BICYCLING
                "2" -> GoogleTravelMode.WALKING
                "3" -> GoogleTravelMode.TRANSIT
                "9" -> GoogleTravelMode.TWO_WHEELER
                else -> null
            }
        }
        return if (stops.size == 1) {
            RouteLink.GoogleMapsDirections(url, Waypoint.CurrentLocation, stops[0], emptyList(), mode)
        } else {
            RouteLink.GoogleMapsDirections(url, stops.first(), stops.last(), stops.subList(1, stops.size - 1), mode)
        }
    }

    private fun komoot(url: String, path: String, query: Map<String, String>): RouteLink {
        KOMOOT_TOUR.find(path)?.let { return RouteLink.KomootTour(url, it.groupValues[1], query["share_token"]) }
        KOMOOT_SMART_TOUR.find(path)?.let { return RouteLink.KomootSmartTour(url, it.groupValues[1]) }
        return RouteLink.Unknown(url)
    }

    private fun garmin(url: String, host: String, path: String): RouteLink {
        if (host == "connect.garmin.com" || host.startsWith("explore.") || host.endsWith(".explore.garmin.com")) {
            val id = GARMIN_COURSE.find(path)?.groupValues?.get(1)
            if (id != null || host.contains("explore")) return RouteLink.GarminCourse(url, id)
        }
        return RouteLink.Unknown(url)
    }

    /** "52.09,5.12" becomes a coordinate, anything else a place to look up. */
    fun waypoint(value: String): Waypoint {
        val text = value.trim()
        COORDINATE.matchEntire(text)?.let { match ->
            val lat = match.groupValues[1].toDouble()
            val lon = match.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return Waypoint.Coordinate(GeoPoint(lat, lon))
        }
        return Waypoint.Place(text)
    }

    private fun travelMode(value: String?): GoogleTravelMode? = when (value?.lowercase()) {
        "driving" -> GoogleTravelMode.DRIVING
        "walking" -> GoogleTravelMode.WALKING
        "bicycling" -> GoogleTravelMode.BICYCLING
        "transit" -> GoogleTravelMode.TRANSIT
        "two-wheeler", "two_wheeler" -> GoogleTravelMode.TWO_WHEELER
        else -> null
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = decode(pair.substringBefore('='))
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
            key to value
        }.toMap()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }
}
