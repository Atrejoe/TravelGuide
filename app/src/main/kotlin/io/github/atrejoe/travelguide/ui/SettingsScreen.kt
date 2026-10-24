package io.github.atrejoe.travelguide.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.atrejoe.travelguide.CrashReporting
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.NeuralVoice
import io.github.atrejoe.travelguide.voice.Narrators
import io.github.atrejoe.travelguide.voice.PreviewNarrator
import io.github.atrejoe.travelguide.voice.SystemVoice
import io.github.atrejoe.travelguide.voice.SystemVoices
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import io.github.atrejoe.travelguide.voice.VoiceEngine
import io.github.atrejoe.travelguide.voice.VoicePackage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.Locale

/** What the voice preview says, per story language. */
private val SAMPLES = mapOf(
    "en" to listOf(
        "Welcome aboard.",
        "As we travel, I'll tell you about the places we pass: the legends behind their names, " +
            "the battles they survived, and the people who shaped them.",
    ),
    "nl" to listOf(
        "Welkom aan boord.",
        "Onderweg vertel ik je over de plaatsen waar we langskomen: de legendes achter hun namen, " +
            "de veldslagen die ze doorstonden en de mensen die ze vormden.",
    ),
)

private const val PRELOAD_DELAY_MILLIS = 700L

/** How fast a voice renders on this phone, measured during the preview. */
private fun speedInfo(label: String, factor: Double): String {
    val seconds = String.format(Locale.ROOT, "%.1f", factor)
    val verdict = if (factor > 1.1) "too slow for this phone, so expect pauses between sentences" else "fast enough"
    return "$label renders 1 second of speech in $seconds s: $verdict."
}

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
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.language) { systemVoices = SystemVoices.load(context, settings.language) }

    // Load the selected voice in the background, so "Preview voice" can start speaking at once.
    val preview = remember { PreviewNarrator(context, models) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(preview) {
        repo.settings.distinctUntilChangedBy { it.voiceId to it.language }.collectLatest { s ->
            delay(PRELOAD_DELAY_MILLIS) // Skip voices only passed by while choosing.
            preview.get(s)
        }
    }

    fun select(voiceId: String) = scope.launch { repo.update { it.copy(voiceId = voiceId) } }

    /** Stories in another language need a voice that speaks it. */
    fun selectLanguage(lang: String) = scope.launch {
        repo.update { s ->
            val keep = VoiceCatalog.neural(s.voiceId)?.lang == lang
            s.copy(language = lang, voiceId = if (keep) s.voiceId else VoiceCatalog.defaultVoiceId(lang))
        }
    }

    fun download(pkg: VoicePackage, then: NeuralVoice) = scope.launch {
        try {
            models.download(pkg)
            select(then.voiceId)
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val voices = VoiceCatalog.voicesFor(settings.language)
    val kokoro = voices.filter { it.pkg.engine == VoiceEngine.KOKORO }
    val piper = voices.filter { it.pkg.engine == VoiceEngine.PIPER }

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
            item { Text("Language", style = MaterialTheme.typography.titleLarge) }
            item { LanguagePicker(settings.language, ::selectLanguage) }
            item {
                Text(
                    "Stories come from the Wikipedia in this language and are told by a voice that speaks it. " +
                        "The app's own screens stay in English.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { HorizontalDivider() }

            item { Text("Narrator", style = MaterialTheme.typography.titleLarge) }
            item {
                Text(
                    "On-device voices sound natural, work offline and cost nothing to use. They are downloaded once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (kokoro.isNotEmpty()) item {
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

            if (kokoro.isNotEmpty()) item { HorizontalDivider() }
            if (piper.isNotEmpty()) item {
                Column {
                    if (kokoro.isNotEmpty()) {
                        Text("Lite voices", style = MaterialTheme.typography.titleMedium)
                        Text("Piper · smaller and faster, but less natural.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("On-device voices", style = MaterialTheme.typography.titleMedium)
                        Text("Piper · about ${piper.first().pkg.approxSizeMb} MB each.", style = MaterialTheme.typography.bodySmall)
                    }
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
                        onClick = {
                            val playing = previewJob
                            if (playing != null) {
                                // Cut the sentence off right away; cancelling ends the preview.
                                preview.stop()
                                playing.cancel()
                                previewJob = null
                            } else {
                                previewInfo = "Loading voice…"
                                previewJob = scope.launch {
                                    try {
                                        val choice = preview.get(settings)
                                        previewInfo = choice.warning ?: "Speaking with ${choice.narrator.label}"
                                        val sample = SAMPLES[settings.language] ?: SAMPLES.getValue("en")
                                        val pieces = Narrators.pieces(choice.narrator, sample)
                                        choice.narrator.prepare(pieces.map { it.text })
                                        for (piece in pieces) {
                                            if (!choice.narrator.speak(piece.text)) break
                                        }
                                        choice.narrator.drain()
                                        choice.narrator.realTimeFactor?.let { factor ->
                                            previewInfo = speedInfo(choice.narrator.label, factor)
                                        }
                                    } finally {
                                        // Unless a newer preview has started meanwhile.
                                        if (previewJob === coroutineContext.job) previewJob = null
                                    }
                                }
                            }
                        },
                    ) { Text(if (previewJob != null) "Stop preview" else "Preview voice") }
                    previewInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                }
            }

            item { HorizontalDivider() }
            item { Text("Stories", style = MaterialTheme.typography.titleLarge) }
            item {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = settings.walkingPlaces,
                        onValueChange = { on -> scope.launch { repo.update { it.copy(walkingPlaces = on) } } },
                        role = Role.Switch,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Buildings and institutions while walking", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "On foot, also tell about the town you enter and the town hall, churches, museums and " +
                                "listed buildings you pass, from OpenStreetMap and Wikidata.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = settings.walkingPlaces, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
                }
            }
            item { StorytellerSettings(settings) }

            item { HorizontalDivider() }
            item { NavigationSettings() }

            if (CrashReporting.available) {
                item { HorizontalDivider() }
                item { CrashReportSettings() }
            }

            item { HorizontalDivider() }
            item {
                Text(
                    "Stories come from Wikipedia (CC BY-SA 4.0), and for buildings from OpenStreetMap (ODbL) and Wikidata (CC0). Map © OpenStreetMap contributors (ODbL). Roads, railways and buildings: Overpass API. Routing: OSRM (routing.openstreetmap.de, FOSSGIS) and Nominatim. Voices: Kokoro (Apache 2.0) and Piper models run " +
                        "with sherpa-onnx (Apache 2.0). No AI service is used while guiding, unless you choose Gemini Nano or Claude as the storyteller.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The story languages (issue #68), each in its own name. */
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StoryLanguages.all.forEach { language ->
            FilterChip(
                selected = language.code == selected,
                onClick = { onSelect(language.code) },
                label = { Text(language.nativeName) },
            )
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

/** Settings → Crash reports (issue #56): opt in to sending crash reports to Sentry. */
@Composable
private fun CrashReportSettings() {
    val context = LocalContext.current
    val enabled by CrashReporting.enabled.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = enabled,
            onValueChange = { CrashReporting.setEnabled(context, it) },
            role = Role.Switch,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send crash reports", style = MaterialTheme.typography.bodyLarge)
            Text(
                "When the app crashes, send a report to the developer through Sentry: the error, the app and " +
                    "Android version and the phone model. Never your location, places or stories.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}
