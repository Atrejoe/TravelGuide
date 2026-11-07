package io.github.atrejoe.travelguide.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.core.nav.ImportedRoute
import io.github.atrejoe.travelguide.core.nav.NavigationStatus
import io.github.atrejoe.travelguide.core.nav.RouteOrigin
import io.github.atrejoe.travelguide.core.story.RelativePosition
import java.util.Locale
import kotlin.math.roundToInt

/*
 * What the app shows about routes, positions and navigation, in the app's language (issue #92).
 * The core gives the facts; the words are the app's.
 */

/** The activity behind a composable's context, to recreate it after a change of language. */
fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** "Komoot tour 'Name' · 42 km". */
fun routeSummary(context: Context, route: ImportedRoute): String {
    val kind = context.getString(
        when (route.origin) {
            RouteOrigin.KOMOOT -> R.string.route_komoot
            RouteOrigin.GOOGLE_MAPS -> R.string.route_google
            RouteOrigin.GARMIN -> R.string.route_garmin
            RouteOrigin.OSMAND -> R.string.route_osmand
            RouteOrigin.WAZE -> R.string.route_waze
            RouteOrigin.GPX -> R.string.route_gpx
            RouteOrigin.FIT -> R.string.route_fit
        },
    )
    val named = route.name?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.route_named, kind, it) } ?: kind
    return context.getString(R.string.route_summary, named, distance(route.lengthM))
}

@Composable
fun routeSummary(route: ImportedRoute): String = routeSummary(LocalContext.current, route)

/** "left · 800 m", for the timeline. */
fun whereLabel(context: Context, relative: RelativePosition): String = when (relative) {
    RelativePosition.Here -> context.getString(R.string.where_here)
    is RelativePosition.Ahead -> context.getString(R.string.where_ahead, distance(relative.distanceM))
    is RelativePosition.Side ->
        context.getString(if (relative.left) R.string.where_left else R.string.where_right, distance(relative.distanceM))
    is RelativePosition.Behind -> context.getString(R.string.where_behind, distance(relative.distanceM))
    is RelativePosition.Compass -> context.getString(R.string.where_compass, compass(context, relative.bearingDeg), distance(relative.distanceM))
}

/** "Google Maps: Turn left onto Main Street · 200 m". */
fun navigationLabel(context: Context, status: NavigationStatus): String =
    status.instruction?.let { context.getString(R.string.navigation_app, status.app.name, it) }
        ?: context.getString(R.string.navigation_app_navigating, status.app.name)

private val compassNames = intArrayOf(
    R.string.compass_n, R.string.compass_ne, R.string.compass_e, R.string.compass_se,
    R.string.compass_s, R.string.compass_sw, R.string.compass_w, R.string.compass_nw,
)

private fun compass(context: Context, bearing: Double): String =
    context.getString(compassNames[(((bearing % 360 + 360) % 360 + 22.5) / 45).toInt() % 8])

/** "800 m", "2.5 km", "42 km": numbers as the screen's language writes them. */
private fun distance(meters: Double): String = when {
    meters < 1_000 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} m"
    meters < 10_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1_000)
    else -> "${(meters / 1_000).roundToInt()} km"
}

/** Where a route came from, e.g. "Komoot" or "GPX file". */
@Composable
fun originLabel(origin: RouteOrigin): String = when (origin) {
    RouteOrigin.GPX -> stringResource(R.string.origin_gpx)
    RouteOrigin.FIT -> stringResource(R.string.origin_fit)
    else -> origin.label
}
