package io.github.atrejoe.travelguide.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import org.osmdroid.util.GeoPoint as OsmPoint
import io.github.atrejoe.travelguide.core.geo.GeoPoint as CorePoint

private data class MapColors(val route: Int, val guess: Int, val you: Int, val story: Int, val upcoming: Int)

/**
 * OpenStreetMap view of where you are, the stretch ahead (solid when it comes from a route, nearly
 * solid when it follows a navigation app's directions, dashed when it follows the road or the
 * railway, and thin and dashed when it is guessed from your movement), the places of the current
 * story and the ones coming up. Follows your position until you pan the map; the button
 * re-centres it.
 */
@Composable
fun GuideMap(
    state: GuideUiState,
    modifier: Modifier = Modifier,
    /** Where the planned route came from, e.g. "Komoot", for the legend. */
    routeFrom: String? = null,
    /** The navigation app whose directions are followed live, e.g. "Google Maps". */
    navigatingWith: String? = null,
) {
    val labels = RouteLabels(
        planned = "Route from " + (routeFrom ?: "navigation"),
        navigation = "Following " + (navigatingWith ?: "navigation") + " directions",
    )
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val colors = MapColors(
        route = scheme.primary.toArgb(),
        guess = scheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb(),
        you = Color(0xFF1E88E5).toArgb(),
        story = scheme.primary.toArgb(),
        upcoming = scheme.secondary.toArgb(),
    )
    var follow by remember { mutableStateOf(true) }
    val map = remember { createMap(context) { follow = false } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            AndroidView(
                factory = { map },
                modifier = Modifier.fillMaxWidth().height(240.dp),
                update = { render(it, state, colors, density, follow, labels) },
            )
            if (!follow) {
                SmallFloatingActionButton(
                    onClick = { follow = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) { Text("◎") }
            }
        }
        MapLegend(state.routeSource, colors, labels)
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createMap(context: Context, onUserPan: () -> Unit): MapView {
    Configuration.getInstance().apply {
        // OpenStreetMap's tile policy asks apps to identify themselves.
        userAgentValue = UrlConnectionFetcher.USER_AGENT
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(osmdroidBasePath, "tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(14.0)
        setOnTouchListener { view, event ->
            // Keep the surrounding screen from scrolling while the map is dragged.
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_MOVE) onUserPan()
            false
        }
    }
}

/** Legend and map labels for routes whose name depends on where they came from. */
private data class RouteLabels(val planned: String, val navigation: String)

/** How the stretch ahead is drawn: the better founded, the bolder and the less dashed. */
private data class LineStyle(val guessed: Boolean, val widthDp: Float, val dashDp: Pair<Float, Float>?)

private fun lineStyle(source: PathSource): LineStyle = when (source) {
    PathSource.PLANNED_ROUTE -> LineStyle(guessed = false, widthDp = 6f, dashDp = null)
    PathSource.NAVIGATION -> LineStyle(guessed = false, widthDp = 6f, dashDp = 24f to 4f)
    PathSource.RAILWAY -> LineStyle(guessed = false, widthDp = 5f, dashDp = 18f to 6f)
    PathSource.ROAD -> LineStyle(guessed = false, widthDp = 4.5f, dashDp = 12f to 7f)
    PathSource.DEAD_RECKONING, PathSource.NONE -> LineStyle(guessed = true, widthDp = 4f, dashDp = 10f to 8f)
}

private fun routeTitle(source: PathSource, labels: RouteLabels): String = when (source) {
    PathSource.PLANNED_ROUTE -> labels.planned
    PathSource.NAVIGATION -> labels.navigation
    PathSource.RAILWAY -> "Following the railway"
    PathSource.ROAD -> "Following the road"
    PathSource.DEAD_RECKONING, PathSource.NONE -> "Estimated direction"
}

private fun render(map: MapView, state: GuideUiState, colors: MapColors, density: Float, follow: Boolean, labels: RouteLabels) {
    map.overlays.clear()
    // Required attribution for OpenStreetMap data.
    map.overlays += CopyrightOverlay(map.context)

    if (state.route.size >= 2) {
        val style = lineStyle(state.routeSource)
        map.overlays += Polyline(map).apply {
            setPoints(state.route.map { it.osm() })
            title = routeTitle(state.routeSource, labels)
            outlinePaint.apply {
                color = if (style.guessed) colors.guess else colors.route
                strokeWidth = style.widthDp * density
                strokeCap = Paint.Cap.ROUND
                pathEffect = style.dashDp?.let { (on, off) -> DashPathEffect(floatArrayOf(on * density, off * density), 0f) }
            }
        }
    }

    for (item in state.upcoming) {
        map.overlays += dot(map, item.point, colors.upcoming, 9, density, item.title, item.where)
    }
    state.current?.places?.forEachIndexed { index, place ->
        map.overlays += dot(map, place.point, colors.story, if (index == 0) 13 else 10, density, place.title, "In the current story")
    }
    state.position?.let { here ->
        map.overlays += dot(map, here, colors.you, 14, density, "You", null)
        if (follow) map.controller.animateTo(here.osm())
    }
    map.invalidate()
}

private fun dot(map: MapView, point: CorePoint, color: Int, sizeDp: Int, density: Float, title: String, snippet: String?) =
    Marker(map).apply {
        position = point.osm()
        this.title = title
        this.snippet = snippet
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        val px = (sizeDp * density).toInt()
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
            setSize(px, px)
        }
    }

private fun CorePoint.osm() = OsmPoint(lat, lon)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapLegend(source: PathSource, colors: MapColors, labels: RouteLabels) {
    val style = MaterialTheme.typography.labelSmall
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (source) {
            PathSource.PLANNED_ROUTE -> LegendLine(Color(colors.route), dashed = false) { Text(labels.planned, style = style) }
            PathSource.NAVIGATION -> LegendLine(Color(colors.route), dashed = false) { Text(labels.navigation, style = style) }
            PathSource.RAILWAY -> LegendLine(Color(colors.route), dashed = true) { Text("Following the railway", style = style) }
            PathSource.ROAD -> LegendLine(Color(colors.route), dashed = true) { Text("Following the road", style = style) }
            PathSource.DEAD_RECKONING -> LegendLine(Color(colors.guess), dashed = true) { Text("Direction guessed from your movement", style = style) }
            PathSource.NONE -> Text("Direction not known yet", style = style)
        }
        LegendDot(Color(colors.you)) { Text("You", style = style) }
        LegendDot(Color(colors.story)) { Text("Current story", style = style) }
        LegendDot(Color(colors.upcoming)) { Text("Coming up", style = style) }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, label: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(22.dp).height(10.dp)) {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 3.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Box(Modifier.padding(start = 4.dp)) { label() }
    }
}

@Composable
private fun LegendDot(color: Color, label: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Box(Modifier.padding(start = 4.dp)) { label() }
    }
}
