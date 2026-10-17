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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.atrejoe.travelguide.voice.NeuralVoice
import io.github.atrejoe.travelguide.voice.Narrators
import io.github.atrejoe.travelguide.voice.SystemVoice
import io.github.atrejoe.travelguide.voice.SystemVoices
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import io.github.atrejoe.travelguide.voice.VoiceEngine
import io.github.atrejoe.travelguide.voice.VoicePackage
import kotlinx.coroutines.launch
import java.util.Locale

private val SAMPLE = listOf(
    "Welcome aboard.",
    "As we travel, I'll tell you about the places we pass: the legends behind their names, " +
        "the battles they survived, and the people who shaped them.",
)

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
    var previewInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { systemVoices = SystemVoices.load(context) }

    fun select(voiceId: String) = scope.launch { repo.update { it.copy(voiceId = voiceId) } }

    fun download(pkg: VoicePackage, then: NeuralVoice) = scope.launch {
        try {
            models.download(pkg)
            select(then.voiceId)
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val kokoro = VoiceCatalog.neuralVoices.filter { it.pkg.engine == VoiceEngine.KOKORO }
    val piper = VoiceCatalog.neuralVoices.filter { it.pkg.engine == VoiceEngine.PIPER }

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

            item {
                PackageHeader(
                    title = "Natural voices",
                    subtitle = "Kokoro · one ${VoiceCatalog.KOKORO_EN.approxSizeMb} MB download for all four voices. " +
                        "Most lifelike; needs a reasonably recent phone.",
                    pkg = VoiceCatalog.KOKORO_EN,
                    installed = VoiceCatalog.KOKORO_EN.id in installed,
                    progress = progress[VoiceCatalog.KOKORO_EN.id],
                    onDownload = { download(VoiceCatalog.KOKORO_EN, kokoro.first()) },
                    onRemove = { models.delete(VoiceCatalog.KOKORO_EN) },
                )
            }
            voiceRows(kokoro, settings.voiceId, enabled = VoiceCatalog.KOKORO_EN.id in installed) { select(it.voiceId) }

            item { HorizontalDivider() }
            item {
                Column {
                    Text("Lite voices", style = MaterialTheme.typography.titleMedium)
                    Text("Piper · smaller and faster, but less natural.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(piper, key = { it.id }) { voice ->
                val pkgInstalled = voice.pkg.id in installed
                Column {
                    VoiceRow(voice, selected = settings.voiceId == voice.voiceId, enabled = pkgInstalled) { select(voice.voiceId) }
                    PackageActions(
                        pkg = voice.pkg,
                        installed = pkgInstalled,
                        progress = progress[voice.pkg.id],
                        onDownload = { download(voice.pkg, voice) },
                        onRemove = { models.delete(voice.pkg) },
                    )
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
                VoiceSlider("Speaking rate", settings.rate, 0.7f..1.2f) { value ->
                    scope.launch { repo.update { it.copy(rate = value) } }
                }
            }
            item {
                VoiceSlider("Pitch (1.00 sounds most natural)", settings.pitch, 0.8f..1.2f) { value ->
                    scope.launch { repo.update { it.copy(pitch = value) } }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        enabled = !previewing,
                        onClick = {
                            previewing = true
                            previewInfo = "Loading voice…"
                            scope.launch {
                                val choice = Narrators.create(context, settings, models)
                                previewInfo = choice.warning ?: "Speaking with ${choice.narrator.label}"
                                try {
                                    SAMPLE.forEachIndexed { i, sentence ->
                                        choice.narrator.speak(sentence, upcoming = SAMPLE.getOrNull(i + 1))
                                    }
                                } finally {
                                    choice.narrator.release()
                                    previewing = false
                                }
                            }
                        },
                    ) { Text(if (previewing) "Speaking…" else "Preview voice") }
                    previewInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                }
            }

            item { HorizontalDivider() }
            item { NavigationSettings() }

            item { HorizontalDivider() }
            item {
                Text(
                    "Stories come from Wikipedia (CC BY-SA 4.0). Map © OpenStreetMap contributors (ODbL). Routes for shared Google Maps links: OSRM (routing.openstreetmap.de, FOSSGIS) and Nominatim. Voices: Kokoro (Apache 2.0) and Piper models run " +
                        "with sherpa-onnx (Apache 2.0). No AI service is used while guiding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.voiceRows(voices: List<NeuralVoice>, selectedId: String, enabled: Boolean, onSelect: (NeuralVoice) -> Unit) {
    items(voices, key = { it.id }) { voice ->
        VoiceRow(voice, selected = selectedId == voice.voiceId, enabled = enabled) { onSelect(voice) }
    }
}

@Composable
private fun VoiceRow(voice: NeuralVoice, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(voice.name, style = MaterialTheme.typography.titleMedium)
            Text(voice.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PackageHeader(
    title: String,
    subtitle: String,
    pkg: VoicePackage,
    installed: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text("$title · recommended", style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            PackageActions(pkg, installed, progress, onDownload, onRemove)
        }
    }
}

@Composable
private fun PackageActions(
    pkg: VoicePackage,
    installed: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    when {
        progress != null -> {
            Text(if (progress < 0f) "Unpacking…" else "Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            if (progress < 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        installed -> TextButton(onClick = onRemove) { Text("Remove download") }
        else -> OutlinedButton(onClick = onDownload) { Text("Download · ${pkg.approxSizeMb} MB") }
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${String.format(Locale.ROOT, "%.2f", current)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = range,
        )
    }
}
