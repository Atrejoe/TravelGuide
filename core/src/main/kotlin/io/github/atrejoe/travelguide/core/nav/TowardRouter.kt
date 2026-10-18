package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.road.RoadGraph

/** Computes a route from where we are towards a signposted place ("towards Utrecht"). */
fun interface TowardRouting {
    /** The route, or null when the place can't be found or reached. Throws on network errors. */
    suspend fun route(from: GeoPoint, toward: String, mode: TravelMode): List<GeoPoint>?
}

/**
 * [TowardRouting] with Nominatim (to find the place, only near where we are) and OSRM (to route
 * there). Places are looked up once per name; Nominatim's one-request-per-second limit is kept
 * by [NominatimClient].
 */
class TowardRouter(
    private val nominatim: NominatimClient,
    private val osrm: OsrmClient,
) : TowardRouting {
    private val places = HashMap<String, GeoPoint?>()

    override suspend fun route(from: GeoPoint, toward: String, mode: TravelMode): List<GeoPoint>? {
        val key = ManeuverParser.normalize(toward)
        val place = if (places.containsKey(key)) {
            places[key]
        } else {
            nominatim.search(toward, near = from, nearKm = SEARCH_KM)?.point.also { places[key] = it }
        } ?: return null
        val distance = GeoMath.distance(from, place)
        // Too close to say anything about the way there, or not plausibly what the sign means.
        if (distance < MIN_DISTANCE_M || distance > SEARCH_KM * 1_000 * 1.5) return null
        val profile = when (mode) {
            TravelMode.CYCLING -> OsrmProfile.BIKE
            TravelMode.WALKING -> OsrmProfile.FOOT
            else -> OsrmProfile.CAR
        }
        val route = osrm.route(listOf(from, place), profile).points
        // Only the first stretch matters for the forecast; keep it light.
        return RoadGraph.cut(route, MAX_LENGTH_M).takeIf { it.size >= 2 }
    }

    private companion object {
        const val SEARCH_KM = 100.0
        const val MIN_DISTANCE_M = 2_000.0
        const val MAX_LENGTH_M = 30_000.0
    }
}
