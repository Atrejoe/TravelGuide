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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.StoryItem
import io.github.atrejoe.travelguide.UpcomingItem
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.ImportedRoute
import io.github.atrejoe.travelguide.core.nav.NavigationStatus
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.story.PoiSources
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Shown as soon as guiding starts, so the screen doesn't jump when the first fix arrives.
            if (state.running) {
                GuideMap(state, routeFrom = route?.origin?.let { originLabel(it) }, navigatingWith = navigation?.app?.name)
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
                                Text(stringResource(R.string.voice_not_downloaded), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.voice_not_downloaded_help, neural.name, neural.pkg.approxSizeMb),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.choose_download_voice)) }
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
                            Button(onClick = start, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.start_guiding)) }
                        } else {
                            Button(
                                onClick = {
                                    GuideService.send(context, if (state.paused) GuideService.ACTION_RESUME else GuideService.ACTION_PAUSE)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(if (state.paused) R.string.resume else R.string.pause)) }
                            OutlinedButton(onClick = { GuideService.send(context, GuideService.ACTION_STOP) }) { Text(stringResource(R.string.stop)) }
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
                        stringResource(R.string.wikipedia_credit),
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

    item { Text(stringResource(R.string.timeline), style = MaterialTheme.typography.labelLarge) }
    itemsIndexed(upcoming, key = { index, u -> "up:$index:${u.title}" }) { index, story ->
        TimelineRow(etaLabel(story.expectedAtMillis, now), Marker.UPCOMING, first = index == 0, last = false) {
            UpcomingCard(story)
        }
    }
    item(key = "now") {
        TimelineRow(stringResource(R.string.now), Marker.NOW, first = upcoming.isEmpty(), last = earlier.isEmpty()) {
            val current = state.current
            if (current != null && state.speaking) {
                StoryCard(current, expanded = true, state)
            } else {
                Text(
                    stringResource(if (state.running) R.string.listening_ahead_dots else R.string.start_to_see),
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
            StoryCard(story, expanded = false, state)
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

@Composable
private fun etaLabel(expectedAtMillis: Long?, now: Long): String {
    if (expectedAtMillis == null) return stringResource(R.string.eta_nearby)
    val seconds = (expectedAtMillis - now) / 1000
    return when {
        seconds <= 45 -> stringResource(R.string.eta_soon)
        seconds < 3_600 -> stringResource(R.string.eta_minutes, ((seconds + 30) / 60).coerceAtLeast(1).toInt())
        else -> stringResource(R.string.eta_later)
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
                !state.running -> stringResource(R.string.state_not_guiding)
                state.paused -> state.pauseReason?.let { stringResource(R.string.state_paused_reason, it) } ?: stringResource(R.string.state_paused)
                state.speaking -> stringResource(R.string.state_narrating)
                else -> stringResource(R.string.state_listening)
            }
            Text(headline, style = MaterialTheme.typography.titleLarge)
            if (state.running && !state.paused) ActivityLine(state)
            if (state.running) {
                val mode = state.mode?.let { m ->
                    stringResource(
                        when {
                            !auto -> R.string.mode_set_by_you
                            m == TravelMode.TRAIN && state.onRail -> R.string.mode_detected_rail
                            else -> R.string.mode_detected
                        },
                        modeLabel(m),
                    )
                } ?: stringResource(R.string.mode_detecting)
                val speed = state.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "–"
                Text(stringResource(R.string.status_line, mode, speed, state.knownPlaces), style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            RouteLine(state, route, navigation, onClearRoute)
            if (state.running) {
                state.voiceLabel?.let { Text(stringResource(R.string.status_voice, it), style = MaterialTheme.typography.bodySmall) }
                state.voiceWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** What the guide is doing right now, or what it waits for between stories (issue #65). */
@Composable
private fun ActivityLine(state: GuideUiState) {
    val activity = state.activity
    val text = activity ?: state.waiting?.takeIf { !state.speaking } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activity != null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Which route the guide follows, and which navigation app is active, if any. */
@Composable
private fun RouteLine(state: GuideUiState, route: ImportedRoute?, navigation: NavigationStatus?, onClearRoute: () -> Unit) {
    navigation?.let {
        Text(stringResource(R.string.navigating_with, navigationLabel(LocalContext.current, it)), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (state.running) {
        val forecast = when (state.routeSource) {
            PathSource.NAVIGATION -> navigation?.app?.name?.let { stringResource(R.string.following_directions, it) }
                ?: stringResource(R.string.following_navigation)
            PathSource.ROAD -> stringResource(R.string.following_road)
            else -> null
        }
        forecast?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    if (route == null) {
        if (!state.running) {
            Text(
                stringResource(R.string.route_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.route_line, routeSummary(route)), style = MaterialTheme.typography.bodyMedium)
            val offRoute = state.running && state.position != null &&
                state.routeSource != PathSource.PLANNED_ROUTE && state.routeSource != PathSource.NONE
            if (offRoute) {
                Text(
                    stringResource(R.string.off_route),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        TextButton(onClick = onClearRoute) { Text(stringResource(R.string.clear)) }
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
                    val auto = if (selected == null && detected != null) {
                        stringResource(R.string.mode_auto_detected, modeLabel(detected))
                    } else {
                        stringResource(R.string.mode_auto)
                    }
                    Text(mode?.let { modeLabel(it) } ?: auto)
                },
            )
        }
    }
}

@Composable
private fun StoryCard(story: StoryItem, expanded: Boolean, state: GuideUiState) {
    val context = LocalContext.current
    val replaying = state.replaying == story.timeMillis
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(story.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Subtle on purpose: a small, muted icon that doesn't compete with the story.
                if (state.running && story.sentences.isNotEmpty()) {
                    IconButton(
                        onClick = { GuideService.replay(context, story.timeMillis) },
                        enabled = !replaying,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.play_again),
                            modifier = Modifier.size(20.dp),
                            tint = if (replaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            if (replaying) {
                Text(stringResource(R.string.playing_again), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (story.also.isNotEmpty()) {
                Text(stringResource(R.string.also, story.also.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                story.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
PoiSources.label(story.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, story.url.toUri()))
                },
            )
        }
    }
}

@Composable
fun modeLabel(mode: TravelMode): String = stringResource(
    when (mode) {
        TravelMode.STATIONARY -> R.string.mode_stationary
        TravelMode.WALKING -> R.string.mode_walking
        TravelMode.CYCLING -> R.string.mode_cycling
        TravelMode.DRIVING -> R.string.mode_driving
        TravelMode.TRAIN -> R.string.mode_train
        TravelMode.FLYING -> R.string.mode_flying
    },
)
