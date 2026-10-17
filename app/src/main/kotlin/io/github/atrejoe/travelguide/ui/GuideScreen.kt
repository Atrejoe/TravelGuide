package io.github.atrejoe.travelguide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.motion.TravelMode
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val neural = VoiceCatalog.neural(settings.voiceId)
            if (neural != null && neural.id !in installed) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Narrator voice not downloaded", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${neural.name} (${neural.approxSizeMb} MB) runs fully on your phone. " +
                                    "Until it is downloaded the guide uses the system voice.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onOpenSettings) { Text("Choose & download voice") }
                        }
                    }
                }
            }

            item { StatusCard(state) }

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
                ModeSelector(settings.modeOverride) { mode ->
                    scope.launch { context.app.settings.update { it.copy(modeOverride = mode) } }
                }
            }

            state.current?.let { current ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Now telling", style = MaterialTheme.typography.labelLarge)
                        StoryCard(current, expanded = true)
                    }
                }
            }

            val earlier = state.history.drop(if (state.current != null) 1 else 0)
            if (earlier.isNotEmpty()) {
                item { Text("Earlier", style = MaterialTheme.typography.labelLarge) }
                items(earlier, key = { it.timeMillis }) { StoryCard(it, expanded = false) }
            }

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

@Composable
private fun StatusCard(state: GuideUiState) {
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
                val mode = state.mode?.let(::modeLabel) ?: "Detecting…"
                val speed = state.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "–"
                Text("$mode · $speed · ${state.knownPlaces} places nearby", style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

@Composable
private fun ModeSelector(selected: TravelMode?, onSelect: (TravelMode?) -> Unit) {
    val options = listOf<TravelMode?>(null, TravelMode.WALKING, TravelMode.CYCLING, TravelMode.DRIVING, TravelMode.TRAIN, TravelMode.FLYING)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode?.let(::modeLabel) ?: "Auto") },
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
            Text(
                story.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(story.timeMillis))} · Source: Wikipedia",
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
