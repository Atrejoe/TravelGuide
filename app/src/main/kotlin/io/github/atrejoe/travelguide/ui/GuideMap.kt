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
 * OpenStreetMap view of where you are, the stretch ahead (solid when it comes from navigation,
 * dashed when it is guessed from your movement), the places of the current story and the ones
 * coming up. Follows your position until you pan the map; the button re-centres it.
 */
@Composable
fun GuideMap(state: GuideUiState, modifier: Modifier = Modifier) {
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
                update = { render(it, state, colors, density, follow) },
            )
            if (!follow) {
                SmallFloatingActionButton(
                    onClick = { follow = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) { Text("◎") }
            }
        }
        MapLegend(state.routeSource, colors)
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

private fun render(map: MapView, state: GuideUiState, colors: MapColors, density: Float, follow: Boolean) {
    map.overlays.clear()
    // Required attribution for OpenStreetMap data.
    map.overlays += CopyrightOverlay(map.context)

    if (state.route.size >= 2) {
        val planned = state.routeSource == PathSource.PLANNED_ROUTE
        map.overlays += Polyline(map).apply {
            setPoints(state.route.map { it.osm() })
            title = if (planned) "Route from navigation" else "Estimated direction"
            outlinePaint.apply {
                color = if (planned) colors.route else colors.guess
                strokeWidth = (if (planned) 6f else 4f) * density
                strokeCap = Paint.Cap.ROUND
                pathEffect = if (planned) null else DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
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
private fun MapLegend(source: PathSource, colors: MapColors) {
    val style = MaterialTheme.typography.labelSmall
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (source) {
            PathSource.PLANNED_ROUTE -> LegendLine(Color(colors.route), dashed = false) { Text("Route from navigation", style = style) }
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
