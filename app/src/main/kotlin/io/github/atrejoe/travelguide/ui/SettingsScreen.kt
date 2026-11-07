package io.github.atrejoe.travelguide.ui

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.CrashReporting
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.guide.HistoryRetention
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.nav.RouteRepository
import io.github.atrejoe.travelguide.settings.AppLanguage
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.Narrators
import io.github.atrejoe.travelguide.voice.NeuralVoice
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
    "de" to listOf(
        "Willkommen an Bord.",
        "Unterwegs erzähle ich Ihnen von den Orten, an denen wir vorbeikommen: von den Legenden hinter ihren Namen, " +
            "den Schlachten, die sie überstanden haben, und den Menschen, die sie geprägt haben.",
    ),
    "fr" to listOf(
        "Bienvenue à bord.",
        "En chemin, je vous parlerai des lieux que nous traversons : des légendes derrière leurs noms, " +
            "des batailles qu'ils ont connues et des gens qui les ont façonnés.",
    ),
    "es" to listOf(
        "Bienvenido a bordo.",
        "Durante el viaje le hablaré de los lugares por los que pasamos: las leyendas detrás de sus nombres, " +
            "las batallas que sobrevivieron y las personas que les dieron forma.",
    ),
    "it" to listOf(
        "Benvenuti a bordo.",
        "Durante il viaggio vi racconterò dei luoghi che attraversiamo: le leggende dietro i loro nomi, " +
            "le battaglie a cui sono sopravvissuti e le persone che li hanno plasmati.",
    ),
    "pt" to listOf(
        "Bem-vindo a bordo.",
        "Pelo caminho, vou contar-lhe sobre os lugares por onde passamos: as lendas por trás dos seus nomes, " +
            "as batalhas a que sobreviveram e as pessoas que os moldaram.",
    ),
)

private const val PRELOAD_DELAY_MILLIS = 700L

/** How fast a voice renders on this phone, measured during the preview. */
private fun speedInfo(context: Context, label: String, factor: Double): String {
    val seconds = String.format(Locale.getDefault(), "%.1f", factor)
    return context.getString(if (factor > 1.1) R.string.speed_slow else R.string.speed_fast, label, seconds)
}

/** The name of a language in the language of the screen, as written within a sentence ("néerlandais"). */
@Composable
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(LocalConfiguration.current.locales[0])

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

    /** Stories in another language need a voice that speaks it; null follows the phone (issue #92). */
    fun selectLanguage(chosen: String?) = scope.launch {
        val lang = chosen ?: AppLanguage.phoneLanguage(context)
        repo.update { s ->
            // ElevenLabs voices speak every story language (issue #75).
            val keep = VoiceCatalog.neural(s.voiceId)?.lang == lang || VoiceCatalog.elevenLabsVoice(s.voiceId) != null
            s.copy(language = lang, followPhoneLanguage = chosen == null, voiceId = if (keep) s.voiceId else VoiceCatalog.defaultVoiceId(lang))
        }
    }

    /** The app's screens in another language: they are built anew (issue #92). */
    fun selectAppLanguage(chosen: String?) {
        AppLanguage.choose(context, chosen.orEmpty())
        context.findActivity()?.recreate()
    }

    fun download(pkg: VoicePackage, then: NeuralVoice) = scope.launch {
        try {
            models.download(pkg)
            select(then.voiceId)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.download_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }

    val voices = VoiceCatalog.voicesFor(settings.language)
    val kokoro = voices.filter { it.pkg.engine == VoiceEngine.KOKORO }
    val piper = voices.filter { it.pkg.engine == VoiceEngine.PIPER }
    // One Kokoro package per language: English, or the multilingual one (issue #73).
    val kokoroPkg = kokoro.firstOrNull()?.pkg

    // Which sections are open, and which kind of narrator shows its voices (issue #80).
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(section: String) {
        open = if (section in open) open - section else open + section
    }
    val activeKind = NarratorKind.of(settings.voiceId)
    var shownKind by rememberSaveable(activeKind) { mutableStateOf(activeKind) }

    /** Shows the voices of [kind], and switches to it at once when one of them can speak already. */
    fun chooseKind(kind: NarratorKind) {
        shownKind = kind
        if (kind == activeKind) return
        val ready = when (kind) {
            NarratorKind.NATURAL -> kokoro.firstOrNull { it.pkg.id in installed }?.voiceId
            NarratorKind.LITE -> piper.firstOrNull { it.pkg.id in installed }?.voiceId
            NarratorKind.SYSTEM -> VoiceCatalog.SYSTEM_PREFIX
            NarratorKind.ELEVENLABS -> null // A voice from the account has to be picked.
        }
        ready?.let(::select)
    }

    val voiceSummary = when (activeKind) {
        NarratorKind.NATURAL, NarratorKind.LITE -> VoiceCatalog.neural(settings.voiceId)?.let { stringResource(R.string.voice_on_phone, it.name) }
        NarratorKind.SYSTEM -> VoiceCatalog.systemVoiceName(settings.voiceId)?.takeIf { it.isNotEmpty() }
            ?.let { name -> systemVoices.firstOrNull { it.name == name }?.label ?: name } ?: stringResource(R.string.voice_phone_own)
        NarratorKind.ELEVENLABS -> stringResource(
            R.string.voice_elevenlabs,
            settings.elevenLabsVoiceName.ifBlank { stringResource(R.string.voice_elevenlabs_unnamed) },
        )
    }
    val storyLanguageName = languageName(settings.language)
    val appLanguageCode = AppLanguage.chosen(context).ifEmpty { AppLanguage.phoneLanguage(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CrashNotice() }

            item {
                SettingsSection(
                    stringResource(R.string.section_language),
                    stringResource(
                        R.string.language_summary,
                        StoryLanguages.of(settings.language).nativeName,
                        StoryLanguages.of(appLanguageCode).nativeName,
                    ),
                    "language" in open,
                    { toggle("language") },
                ) {
                    Text(stringResource(R.string.language_stories), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.language_stories_help), style = MaterialTheme.typography.bodySmall)
                    LanguagePicker(
                        selected = settings.language.takeUnless { settings.followPhoneLanguage },
                        phone = AppLanguage.phoneLanguage(context),
                        onSelect = ::selectLanguage,
                    )
                    Text(stringResource(R.string.language_app), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.language_app_help), style = MaterialTheme.typography.bodySmall)
                    LanguagePicker(
                        selected = AppLanguage.chosen(context).ifEmpty { null },
                        phone = AppLanguage.phoneLanguage(context),
                        onSelect = ::selectAppLanguage,
                    )
                    Text(
                        stringResource(R.string.language_phone_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsSection(stringResource(R.string.section_narrator), voiceSummary, "narrator" in open, { toggle("narrator") }) {
                    if (kokoroPkg != null) {
                        OptionRow(
                            stringResource(R.string.narrator_natural),
                            stringResource(R.string.narrator_natural_help, kokoroPkg.approxSizeMb),
                            selected = shownKind == NarratorKind.NATURAL,
                            onSelect = { chooseKind(NarratorKind.NATURAL) },
                        ) {
                            PackageActions(
                                pkg = kokoroPkg,
                                installed = kokoroPkg.id in installed,
                                progress = progress[kokoroPkg.id],
                                onDownload = { download(kokoroPkg, kokoro.first()) },
                                onRemove = { models.delete(kokoroPkg) },
                            )
                            kokoro.forEach { voice ->
                                VoiceRow(voice, selected = settings.voiceId == voice.voiceId, enabled = kokoroPkg.id in installed) {
                                    select(voice.voiceId)
                                }
                            }
                        }
                    }
                    if (piper.isNotEmpty()) {
                        OptionRow(
                            stringResource(if (kokoroPkg != null) R.string.narrator_lite else R.string.narrator_lite_only),
                            stringResource(R.string.narrator_lite_help, piper.minOf { it.pkg.approxSizeMb }, piper.maxOf { it.pkg.approxSizeMb }),
                            selected = shownKind == NarratorKind.LITE,
                            onSelect = { chooseKind(NarratorKind.LITE) },
                        ) {
                            piper.forEach { voice ->
                                val pkgInstalled = voice.pkg.id in installed
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
                    }
                    OptionRow(
                        stringResource(R.string.narrator_system),
                        stringResource(R.string.narrator_system_help, storyLanguageName),
                        selected = shownKind == NarratorKind.SYSTEM,
                        onSelect = { chooseKind(NarratorKind.SYSTEM) },
                    ) {
                        (listOf(SystemVoice("", stringResource(R.string.narrator_system_default))) + systemVoices).forEach { voice ->
                            val id = VoiceCatalog.SYSTEM_PREFIX + voice.name
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = settings.voiceId == id, onClick = { select(id) }, role = Role.RadioButton),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = settings.voiceId == id, onClick = null)
                                Text(voice.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    OptionRow(
                        stringResource(R.string.narrator_elevenlabs),
                        stringResource(R.string.narrator_elevenlabs_help),
                        selected = shownKind == NarratorKind.ELEVENLABS,
                        onSelect = { chooseKind(NarratorKind.ELEVENLABS) },
                    ) {
                        ElevenLabsSettings(settings)
                    }

                    HorizontalDivider()
                    VoiceSlider(stringResource(R.string.speaking_rate), settings.rate, 0.7f..1.2f) { value ->
                        scope.launch { repo.update { it.copy(rate = value) } }
                    }
                    VoiceSlider(stringResource(R.string.pitch), settings.pitch, 0.8f..1.2f) { value ->
                        scope.launch { repo.update { it.copy(pitch = value) } }
                    }
                    Button(
                        onClick = {
                            val playing = previewJob
                            if (playing != null) {
                                // Cut the sentence off right away; cancelling ends the preview.
                                preview.stop()
                                playing.cancel()
                                previewJob = null
                            } else {
                                previewInfo = context.getString(R.string.loading_voice)
                                previewJob = scope.launch {
                                    try {
                                        val choice = preview.get(settings)
                                        previewInfo = choice.warning ?: context.getString(R.string.speaking_with, choice.narrator.label)
                                        val sample = SAMPLES[settings.language] ?: SAMPLES.getValue("en")
                                        val pieces = Narrators.pieces(choice.narrator, sample)
                                        choice.narrator.prepare(pieces.map { it.text })
                                        for (piece in pieces) {
                                            if (!choice.narrator.speak(piece.text)) break
                                        }
                                        choice.narrator.drain()
                                        choice.narrator.realTimeFactor?.let { factor ->
                                            previewInfo = speedInfo(context, choice.narrator.label, factor)
                                        }
                                    } finally {
                                        // Unless a newer preview has started meanwhile.
                                        if (previewJob === coroutineContext.job) previewJob = null
                                    }
                                }
                            }
                        },
                    ) { Text(stringResource(if (previewJob != null) R.string.preview_stop else R.string.preview_voice)) }
                    previewInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                }
            }

            item {
                SettingsSection(stringResource(R.string.section_stories), storytellerLabel(settings.storyteller), "stories" in open, { toggle("stories") }) {
                    SwitchRow(
                        stringResource(R.string.walking_places),
                        stringResource(R.string.walking_places_help),
                        settings.walkingPlaces,
                    ) { on -> scope.launch { repo.update { it.copy(walkingPlaces = on) } } }
                    SwitchRow(
                        stringResource(R.string.translate_places, storyLanguageName),
                        stringResource(R.string.translate_places_help),
                        settings.translatePlaces,
                    ) { on -> scope.launch { repo.update { it.copy(translatePlaces = on) } } }
                    HorizontalDivider()
                    StorytellerSettings(settings)
                }
            }

            item {
                val remembered by context.app.storyHistory.count.collectAsStateWithLifecycle()
                val retention = HistoryRetention.byName(settings.historyRetention)
                SettingsSection(
                    stringResource(R.string.section_history),
                    when (retention) {
                        HistoryRetention.TRIP -> stringResource(R.string.history_trip_only)
                        HistoryRetention.ALWAYS -> stringResource(R.string.history_count, remembered)
                        else -> stringResource(R.string.history_count_for, remembered, retentionLabel(retention))
                    },
                    "history" in open,
                    { toggle("history") },
                ) { HistorySettings(settings) }
            }

            item {
                val route by RouteRepository.route.collectAsStateWithLifecycle()
                SettingsSection(
                    stringResource(R.string.section_navigation),
                    route?.let { stringResource(R.string.navigation_following, routeSummary(it)) } ?: stringResource(R.string.navigation_guessing),
                    "navigation" in open,
                    { toggle("navigation") },
                ) { NavigationSettings() }
            }

            if (CrashReporting.available) item {
                val enabled by CrashReporting.enabled.collectAsStateWithLifecycle()
                SettingsSection(stringResource(R.string.section_crashes), stringResource(if (enabled) R.string.crashes_sent else R.string.crashes_not_sent), "crashes" in open, { toggle("crashes") }) {
                    CrashReportSettings()
                }
            }

            item {
                Text(
                    stringResource(R.string.credits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The kinds of narrator to choose from (issue #80); the voice id says which one is in use. */
private enum class NarratorKind {
    NATURAL,
    LITE,
    SYSTEM,
    ELEVENLABS,
    ;

    companion object {
        fun of(voiceId: String): NarratorKind = when {
            VoiceCatalog.elevenLabsVoice(voiceId) != null -> ELEVENLABS
            else -> when (VoiceCatalog.neural(voiceId)?.pkg?.engine) {
                VoiceEngine.KOKORO -> NATURAL
                VoiceEngine.PIPER -> LITE
                null -> SYSTEM
            }
        }
    }
}

/** A setting that is on or off, with what it does. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * The languages (issue #68), each in its own name, after the phone's: [selected] null follows the
 * phone (issue #92), whose language is [phone].
 */
@Composable
private fun LanguagePicker(selected: String?, phone: String, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.language_phone, StoryLanguages.of(phone).nativeName)) },
        )
        StoryLanguages.all.forEach { language ->
            FilterChip(
                selected = language.code == selected,
                onClick = { onSelect(language.code) },
                label = { Text(language.nativeName) },
            )
        }
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
            Text(voiceDescription(voice), style = MaterialTheme.typography.bodySmall)
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
            Text(
                if (progress < 0f) stringResource(R.string.unpacking) else stringResource(R.string.downloading, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
            if (progress < 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        installed -> TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_download)) }
        else -> OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.download_size, pkg.approxSizeMb)) }
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${String.format(Locale.getDefault(), "%.2f", current)}", style = MaterialTheme.typography.bodyMedium)
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
            Text(stringResource(R.string.crash_reports_send), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.crash_reports_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}
