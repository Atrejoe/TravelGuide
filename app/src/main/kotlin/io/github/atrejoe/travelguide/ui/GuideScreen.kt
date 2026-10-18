package io.github.atrejoe.travelguide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.StoryItem
import io.github.atrejoe.travelguide.UpcomingItem
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.ImportedRoute
import io.github.atrejoe.travelguide.core.nav.NavigationStatus
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.nav.NavigationState
import io.github.atrejoe.travelguide.nav.RouteRepository
import io.github.atrejoe.travelguide.service.GuideService
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by GuideStatus.state.collectAsStateWithLifecycle()
    val settings by context.app.settings.settings.collectAsState(initial = AppSettings())
    val installed by context.app.voiceModels.installed.collectAsStateWithLifecycle()
    val route by RouteRepository.route.collectAsStateWithLifecycle()
    val navigation by NavigationState.status.collectAsStateWithLifecycle()

    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val located = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (located) GuideService.start(context)
    }
    val start = {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLocation) GuideService.start(context) else launcher.launch(permissions.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel Guide") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.running && state.position != null) {
                GuideMap(state, routeFrom = route?.origin?.label, navigatingWith = navigation?.app?.name)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val neural = VoiceCatalog.neural(settings.voiceId)
                if (neural != null && neural.pkg.id !in installed) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Narrator voice not downloaded", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${neural.name} (${neural.pkg.approxSizeMb} MB) runs fully on your phone. " +
                                        "Until it is downloaded the guide uses the system voice.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = onOpenSettings) { Text("Choose & download voice") }
                            }
                        }
                    }
                }

                item {
                    StatusCard(
                        state,
                        auto = settings.modeOverride == null,
                        route = route,
                        navigation = navigation,
                        onClearRoute = { scope.launch { RouteRepository.clear(context) } },
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!state.running) {
                            Button(onClick = start, modifier = Modifier.weight(1f)) { Text("Start guiding") }
                        } else {
                            Button(
                                onClick = {
                                    GuideService.send(context, if (state.paused) GuideService.ACTION_RESUME else GuideService.ACTION_PAUSE)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (state.paused) "Resume" else "Pause") }
                            OutlinedButton(onClick = { GuideService.send(context, GuideService.ACTION_STOP) }) { Text("Stop") }
                        }
                    }
                }

                item {
                    ModeSelector(settings.modeOverride, detected = state.mode.takeIf { state.running }) { mode ->
                        scope.launch { context.app.settings.update { it.copy(modeOverride = mode) } }
                    }
                }

                timeline(state)

                item {
                    Text(
                        "Stories are based on Wikipedia articles, available under CC BY-SA 4.0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Upcoming stories (furthest first) above "now", earlier stories below, joined by a vertical line
 * with the expected or actual time on the left.
 */
private fun LazyListScope.timeline(state: GuideUiState) {
    val earlier = state.history.drop(if (state.current != null) 1 else 0)
    val upcoming = state.upcoming.reversed()
    if (upcoming.isEmpty() && state.current == null && earlier.isEmpty()) return
    val now = System.currentTimeMillis()

    item { Text("Timeline", style = MaterialTheme.typography.labelLarge) }
    itemsIndexed(upcoming, key = { index, u -> "up:$index:${u.title}" }) { index, story ->
        TimelineRow(etaLabel(story.expectedAtMillis, now), Marker.UPCOMING, first = index == 0, last = false) {
            UpcomingCard(story)
        }
    }
    item(key = "now") {
        TimelineRow("Now", Marker.NOW, first = upcoming.isEmpty(), last = earlier.isEmpty()) {
            val current = state.current
            if (current != null && state.speaking) {
                StoryCard(current, expanded = true)
            } else {
                Text(
                    if (state.running) "Listening to the road ahead…" else "Start guiding to see what's coming up.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    // Once a story has been told it moves below "now".
    val finished = state.current?.takeIf { !state.speaking }
    val past = listOfNotNull(finished) + earlier
    itemsIndexed(past, key = { _, s -> "past:" + s.timeMillis }) { index, story ->
        TimelineRow(clock(story.timeMillis), Marker.PAST, first = false, last = index == past.lastIndex) {
            StoryCard(story, expanded = false)
        }
    }
}

private enum class Marker { UPCOMING, NOW, PAST }

@Composable
private fun TimelineRow(
    label: String,
    marker: Marker,
    first: Boolean,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = when (marker) {
        Marker.UPCOMING -> MaterialTheme.colorScheme.secondary
        Marker.NOW -> MaterialTheme.colorScheme.primary
        Marker.PAST -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(76.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width - 10.dp.toPx()
                    val dotY = 18.dp.toPx()
                    // Reach into the list's 12 dp item spacing so the line looks continuous.
                    val gap = 6.dp.toPx()
                    drawLine(
                        color = line,
                        start = Offset(x, if (first) dotY else -gap),
                        end = Offset(x, if (last) dotY else size.height + gap),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(dot, radius = (if (marker == Marker.NOW) 7 else 5).dp.toPx(), center = Offset(x, dotY))
                },
        ) {
            Text(
                label,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 22.dp),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                color = if (marker == Marker.NOW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f).padding(start = 4.dp)) { content() }
    }
}

@Composable
private fun UpcomingCard(story: UpcomingItem) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(story.title, style = MaterialTheme.typography.titleSmall)
            Text(story.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun etaLabel(expectedAtMillis: Long?, now: Long): String {
    if (expectedAtMillis == null) return "nearby"
    val seconds = (expectedAtMillis - now) / 1000
    return when {
        seconds <= 45 -> "soon"
        seconds < 3_600 -> "in ${((seconds + 30) / 60).coerceAtLeast(1)} min"
        else -> "later"
    }
}

private fun clock(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

@Composable
private fun StatusCard(
    state: GuideUiState,
    auto: Boolean,
    route: ImportedRoute?,
    navigation: NavigationStatus?,
    onClearRoute: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val headline = when {
                !state.running -> "Not guiding"
                state.paused -> "Paused" + (state.pauseReason?.let { " · $it" } ?: "")
                state.speaking -> "Narrating"
                else -> "Listening to the road ahead"
            }
            Text(headline, style = MaterialTheme.typography.titleLarge)
            if (state.running) {
                val mode = state.mode?.let { m ->
                    modeLabel(m) + when {
                        !auto -> " (set by you)"
                        m == TravelMode.TRAIN && state.onRail -> " (detected: on the railway)"
                        else -> " (detected)"
                    }
                } ?: "Detecting…"
                val speed = state.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "–"
                Text("$mode · $speed · ${state.knownPlaces} places nearby", style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            RouteLine(state, route, navigation, onClearRoute)
            if (state.running) {
                state.voiceLabel?.let { Text("Voice: $it", style = MaterialTheme.typography.bodySmall) }
                state.voiceWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Which route the guide follows, and which navigation app is active, if any. */
@Composable
private fun RouteLine(state: GuideUiState, route: ImportedRoute?, navigation: NavigationStatus?, onClearRoute: () -> Unit) {
    navigation?.let {
        Text("Navigating with ${it.label()}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (state.running) {
        val forecast = when (state.routeSource) {
            PathSource.NAVIGATION -> "Following ${navigation?.app?.name ?: "navigation"} directions"
            PathSource.ROAD -> "Following the road"
            else -> null
        }
        forecast?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    if (route == null) {
        if (!state.running) {
            Text(
                "Tip: with notification access the guide follows Google Maps, Komoot, OsmAnd or Waze directions. " +
                    "You can also share a route or a GPX file with Travel Guide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Route: ${route.summary()}", style = MaterialTheme.typography.bodyMedium)
            val offRoute = state.running && state.position != null &&
                state.routeSource != PathSource.PLANNED_ROUTE && state.routeSource != PathSource.NONE
            if (offRoute) {
                Text(
                    "You're more than 1 km from the route, so the guide looks ahead without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        TextButton(onClick = onClearRoute) { Text("Clear") }
    }
}

@Composable
private fun ModeSelector(selected: TravelMode?, detected: TravelMode?, onSelect: (TravelMode?) -> Unit) {
    val options = listOf<TravelMode?>(null, TravelMode.WALKING, TravelMode.CYCLING, TravelMode.DRIVING, TravelMode.TRAIN, TravelMode.FLYING)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = {
                    // In automatic mode, show what was detected: "Auto · Cycling".
                    val auto = if (selected == null && detected != null) "Auto · ${modeLabel(detected)}" else "Auto"
                    Text(mode?.let(::modeLabel) ?: auto)
                },
            )
        }
    }
}

@Composable
private fun StoryCard(story: StoryItem, expanded: Boolean) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(story.title, style = MaterialTheme.typography.titleMedium)
            if (story.also.isNotEmpty()) {
                Text("Also: " + story.also.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                story.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
"Source: Wikipedia",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, story.url.toUri()))
                },
            )
        }
    }
}

fun modeLabel(mode: TravelMode): String = when (mode) {
    TravelMode.STATIONARY -> "Standing still"
    TravelMode.WALKING -> "Walking"
    TravelMode.CYCLING -> "Cycling"
    TravelMode.DRIVING -> "Car"
    TravelMode.TRAIN -> "Train"
    TravelMode.FLYING -> "Plane"
}
