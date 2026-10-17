package io.github.atrejoe.travelguide.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.Narrators
import io.github.atrejoe.travelguide.voice.SystemVoice
import io.github.atrejoe.travelguide.voice.SystemVoices
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import kotlinx.coroutines.launch
import java.util.Locale

private const val SAMPLE =
    "Welcome aboard. As we travel, I will tell you about the places we pass: their history, " +
        "their landscape and the people who made them."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    val models = context.app.voiceModels
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val installed by models.installed.collectAsStateWithLifecycle()
    val progress by models.progress.collectAsStateWithLifecycle()
    var systemVoices by remember { mutableStateOf<List<SystemVoice>>(emptyList()) }
    var previewing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { systemVoices = SystemVoices.load(context) }

    fun select(voiceId: String) = scope.launch { repo.update { it.copy(voiceId = voiceId) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Narrator", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "On-device voices sound natural, work offline and cost nothing to use. They are downloaded once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            items(VoiceCatalog.neuralVoices, key = { it.id }) { voice ->
                val isInstalled = voice.id in installed
                val downloading = progress[voice.id]
                Column {
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = settings.voiceId == voice.voiceId,
                            onClick = { select(voice.voiceId) },
                            role = Role.RadioButton,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.voiceId == voice.voiceId, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(voice.name, style = MaterialTheme.typography.titleMedium)
                            Text(voice.description, style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            downloading != null -> Unit
                            isInstalled -> TextButton(onClick = { models.delete(voice) }) { Text("Remove") }
                            else -> TextButton(onClick = {
                                scope.launch {
                                    try {
                                        models.download(voice)
                                        select(voice.voiceId)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Text("Get · ${voice.approxSizeMb} MB") }
                        }
                    }
                    if (downloading != null) {
                        if (downloading < 0f) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { downloading }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item { Text("System voices", style = MaterialTheme.typography.titleMedium) }
            val systemOptions = listOf(SystemVoice("", "Default system voice")) + systemVoices
            items(systemOptions, key = { "system:" + it.name }) { voice ->
                val id = VoiceCatalog.SYSTEM_PREFIX + voice.name
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = settings.voiceId == id,
                        onClick = { select(id) },
                        role = Role.RadioButton,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.voiceId == id, onClick = null)
                    Text(voice.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            item { HorizontalDivider() }
            item {
                VoiceSlider("Pitch (lower is deeper)", settings.pitch) { value ->
                    scope.launch { repo.update { it.copy(pitch = value) } }
                }
            }
            item {
                VoiceSlider("Speaking rate", settings.rate) { value ->
                    scope.launch { repo.update { it.copy(rate = value) } }
                }
            }
            item {
                Button(
                    enabled = !previewing,
                    onClick = {
                        previewing = true
                        scope.launch {
                            val narrator = Narrators.create(context, settings, models)
                            try {
                                narrator.speak(SAMPLE)
                            } finally {
                                narrator.release()
                                previewing = false
                            }
                        }
                    },
                ) { Text(if (previewing) "Speaking…" else "Preview voice") }
            }

            item { HorizontalDivider() }
            item {
                Text(
                    "Stories come from Wikipedia (CC BY-SA 4.0). Voices: Piper models run with sherpa-onnx " +
                        "(Apache 2.0). No AI service is used while guiding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${String.format(Locale.ROOT, "%.2f", current)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = 0.6f..1.3f,
        )
    }
}
