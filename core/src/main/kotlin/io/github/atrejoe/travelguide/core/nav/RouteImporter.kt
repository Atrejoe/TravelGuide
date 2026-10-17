package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Turns what the user shares with the app (a Google Maps, Komoot or Garmin link, or a GPX/FIT
 * file) into an [ImportedRoute] the guide can follow, or a [RouteImportException] whose message
 * tells the user what to do instead.
 */
class RouteImporter(
    http: HttpFetcher,
    /** Follows short links; without one, maps.app.goo.gl links can't be used. */
    private val resolver: LinkResolver? = null,
    private val nominatim: NominatimClient = NominatimClient(http),
    private val osrm: OsrmClient = OsrmClient(http),
    private val komoot: KomootClient = KomootClient(http),
) {

    /**
     * Imports a shared text or link.
     *
     * @param here the current position, used when a Google Maps route starts at "your location".
     * @param mode the guide's travel mode, used when the link does not say how to travel.
     */
    suspend fun importText(text: String, here: GeoPoint? = null, mode: TravelMode? = null): ImportedRoute {
        var link = RouteLinks.classify(text)
        var hops = 0
        while (link is RouteLink.ShortLink && hops++ < 3) {
            val resolved = resolve(link.url)
            link = RouteLinks.classifyUrl(resolved)
            if (link is RouteLink.Unknown) link = RouteLinks.classify(resolved)
        }
        return when (link) {
            is RouteLink.GoogleMapsDirections -> googleMaps(link, here, mode)
            is RouteLink.KomootTour -> komootTour(link)
            is RouteLink.KomootSmartTour -> throw RouteImportException(
                RouteImportException.Reason.KOMOOT_UNAVAILABLE,
                "Komoot suggestions (smart tours) can't be read. Save the tour in Komoot first, then share it " +
                    "again, or export it as a GPX file and share that file.",
            )
            is RouteLink.GarminCourse -> throw RouteImportException(
                RouteImportException.Reason.GARMIN_LOGIN_REQUIRED,
                "Garmin courses need a Garmin login, so the link can't be read. In Garmin Connect or Garmin " +
                    "Explore, export the course as a GPX file and share that file with Travel Guide.",
            )
            is RouteLink.GoogleMapsOther -> throw RouteImportException(
                RouteImportException.Reason.UNSUPPORTED,
                "This Google Maps link is not a route. In Google Maps, tap Directions, then share the route.",
            )
            is RouteLink.ShortLink -> throw RouteImportException(
                RouteImportException.Reason.NETWORK,
                "Could not open the short link. Check your connection and try again.",
            )
            is RouteLink.Unknown -> throw RouteImportException(
                RouteImportException.Reason.UNSUPPORTED,
                "No route found in what was shared. Share directions from Google Maps, a Komoot tour, or a GPX file.",
            )
        }
    }

    /** Imports a GPX or FIT file. [fileName] is only a hint. */
    fun importFile(bytes: ByteArray, fileName: String? = null): ImportedRoute {
        if (FitCourseParser.isFit(bytes)) {
            val fit = FitCourseParser.parse(bytes)
            return finish(fit.name ?: fileName?.let(::baseName), RouteOrigin.FIT, fit.points)
        }
        val gpx = GpxParser.parse(ByteArrayInputStream(bytes))
        return finish(gpx.name ?: fileName?.let(::baseName), originFromCreator(gpx.creator), gpx.points)
    }

    private suspend fun resolve(url: String): String {
        val r = resolver ?: throw RouteImportException(
            RouteImportException.Reason.UNSUPPORTED,
            "Short links can't be opened here. Open the link in a browser and share the full address.",
        )
        return try {
            r.resolve(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RouteImportException(RouteImportException.Reason.NETWORK, "Could not open the short link. Check your connection.", e)
        }
    }

    private suspend fun googleMaps(link: RouteLink.GoogleMapsDirections, here: GeoPoint?, mode: TravelMode?): ImportedRoute {
        val stops = link.stops.map { stop ->
            when (stop) {
                is Waypoint.Coordinate -> stop.point
                Waypoint.CurrentLocation -> here ?: throw RouteImportException(
                    RouteImportException.Reason.NEEDS_LOCATION,
                    "This route starts at your location, which isn't known yet. Start guiding first, or share " +
                        "a route with a fixed starting point.",
                )
                is Waypoint.Place -> geocode(stop.query)
            }
        }
        val profile = profileFor(link.travelMode, mode)
        val route = try {
            osrm.route(stops, profile)
        } catch (e: RouteImportException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RouteImportException(RouteImportException.Reason.ROUTING_FAILED, "Couldn't reach the routing service. Try again later.", e)
        }
        val name = placeName(link.stops.first(), "start") + " → " + placeName(link.stops.last(), "destination")
        val note = buildString {
            append("Recomputed with OpenStreetMap; it may differ from Google's route.")
            if (link.travelMode == GoogleTravelMode.TRANSIT) append(" Public transport is approximated by road.")
        }
        return finish(name, RouteOrigin.GOOGLE_MAPS, route.points, note)
    }

    private suspend fun geocode(query: String): GeoPoint {
        val place = try {
            nominatim.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RouteImportException(RouteImportException.Reason.GEOCODING_FAILED, "Couldn't look up \"$query\". Check your connection.", e)
        }
        return place?.point ?: throw RouteImportException(
            RouteImportException.Reason.GEOCODING_FAILED,
            "Couldn't find \"$query\" on the map.",
        )
    }

    private suspend fun komootTour(link: RouteLink.KomootTour): ImportedRoute {
        val tour = try {
            komoot.tour(link.id, link.shareToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw komootUnavailable(e)
        }
        if (tour.points.size < 2) throw komootUnavailable(null)
        return finish(tour.name, RouteOrigin.KOMOOT, tour.points)
    }

    private fun komootUnavailable(cause: Throwable?) = RouteImportException(
        RouteImportException.Reason.KOMOOT_UNAVAILABLE,
        "Couldn't load this Komoot tour; it may be private. In Komoot, open the tour, export it as a GPX " +
            "file and share that file with Travel Guide.",
        cause,
    )

    private fun finish(name: String?, origin: RouteOrigin, points: List<GeoPoint>, note: String? = null): ImportedRoute {
        val simplified = RouteSimplifier.simplify(points)
        if (simplified.size < 2) {
            throw RouteImportException(RouteImportException.Reason.NO_ROUTE, "No route found in this file.")
        }
        return ImportedRoute(name, origin, simplified, note)
    }

    companion object {
        /** Picks the OSRM profile from Google's travel mode, or else from how the user travels. */
        fun profileFor(google: GoogleTravelMode?, mode: TravelMode?): OsrmProfile = when (google) {
            GoogleTravelMode.WALKING -> OsrmProfile.FOOT
            GoogleTravelMode.BICYCLING -> OsrmProfile.BIKE
            GoogleTravelMode.DRIVING, GoogleTravelMode.TWO_WHEELER, GoogleTravelMode.TRANSIT -> OsrmProfile.CAR
            null -> when (mode) {
                TravelMode.WALKING -> OsrmProfile.FOOT
                TravelMode.CYCLING -> OsrmProfile.BIKE
                else -> OsrmProfile.CAR
            }
        }

        /** Tells Komoot, Garmin and OsmAnd exports apart from other GPX files. */
        fun originFromCreator(creator: String?): RouteOrigin {
            val c = creator?.lowercase().orEmpty()
            return when {
                "komoot" in c -> RouteOrigin.KOMOOT
                "garmin" in c -> RouteOrigin.GARMIN
                "osmand" in c -> RouteOrigin.OSMAND
                else -> RouteOrigin.GPX
            }
        }

        private fun baseName(fileName: String) = fileName.substringAfterLast('/').substringBeforeLast('.')

        private fun placeName(stop: Waypoint, fallback: String): String = when (stop) {
            is Waypoint.Place -> stop.query.substringBefore(',').trim().ifEmpty { fallback }
            is Waypoint.Coordinate -> fallback
            Waypoint.CurrentLocation -> "your location"
        }
    }
}
