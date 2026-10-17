package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt

/** Where an imported route came from, shown to the user so they know what the guide follows. */
enum class RouteOrigin(val label: String) {
    GOOGLE_MAPS("Google Maps"),
    KOMOOT("Komoot"),
    GARMIN("Garmin"),
    OSMAND("OsmAnd"),
    WAZE("Waze"),
    GPX("GPX file"),
    FIT("FIT file"),
}

/** A planned route the guide can follow. */
data class ImportedRoute(
    /** A name for the route, e.g. the Komoot tour title or "Utrecht → Amersfoort". */
    val name: String?,
    val origin: RouteOrigin,
    val points: List<GeoPoint>,
    /** A caveat worth showing, e.g. that a Google Maps route was recomputed. */
    val note: String? = null,
) {
    val lengthM: Double by lazy { points.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a, b) } }

    /** "Komoot tour 'Name' · 42 km", for the status card. */
    fun summary(): String {
        val kind = when (origin) {
            RouteOrigin.KOMOOT -> "Komoot tour"
            RouteOrigin.GOOGLE_MAPS -> "Google Maps route"
            RouteOrigin.GARMIN -> "Garmin course"
            RouteOrigin.OSMAND -> "OsmAnd route"
            RouteOrigin.WAZE -> "Waze route"
            RouteOrigin.GPX -> "GPX route"
            RouteOrigin.FIT -> "FIT course"
        }
        val title = name?.takeIf { it.isNotBlank() }?.let { " '$it'" }.orEmpty()
        return "$kind$title · ${formatDistance(lengthM)}"
    }

    companion object {
        fun formatDistance(meters: Double): String = when {
            meters < 1_000 -> "${meters.roundToInt()} m"
            meters < 10_000 -> String.format(Locale.ROOT, "%.1f km", meters / 1_000)
            else -> "${(meters / 1_000).roundToInt()} km"
        }
    }
}

/** Why a route could not be imported; [message] is written for the user. */
class RouteImportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** Not a link or file we know how to read. */
        UNSUPPORTED,

        /** The file was read but holds no usable points. */
        NO_ROUTE,

        /** Garmin Connect / Explore links need a login; the user should share a GPX export. */
        GARMIN_LOGIN_REQUIRED,

        /** Komoot did not hand out the tour, typically because it is private. */
        KOMOOT_UNAVAILABLE,

        /** The link starts at "your location" and we don't know it yet. */
        NEEDS_LOCATION,

        /** A place name in the link could not be found. */
        GEOCODING_FAILED,

        /** The routing service found no route or could not be reached. */
        ROUTING_FAILED,

        /** A network problem, e.g. while resolving a short link. */
        NETWORK,
    }
}
