package io.github.atrejoe.travelguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.voice.CloudUsage
import io.github.atrejoe.travelguide.core.voice.CloudVoice
import io.github.atrejoe.travelguide.core.voice.ElevenLabsClient
import io.github.atrejoe.travelguide.core.voice.ElevenLabsException
import io.github.atrejoe.travelguide.core.voice.ElevenLabsModel
import io.github.atrejoe.travelguide.net.UrlConnectionTransport
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings → Narrator → ElevenLabs (issue #75): voices from the listener's own ElevenLabs account,
 * with their own API key. Credits used and left are shown, as a story costs credits.
 */
@Composable
fun ElevenLabsSettings(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    val keys = context.app.elevenLabsKeys
    val hasKey by keys.hasKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<CloudVoice>?>(null) }
    var usage by remember { mutableStateOf<CloudUsage?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val language = StoryLanguages.of(settings.language)

    LaunchedEffect(hasKey, reload) {
        voices = null
        usage = null
        problem = null
        val key = if (hasKey) keys.get() else null
        if (key == null) return@LaunchedEffect
        val client = ElevenLabsClient(key, UrlConnectionTransport())
        try {
            voices = client.voices()
            // A key may lack the permission to read the account; the voices still work then.
            usage = try {
                client.usage()
            } catch (e: ElevenLabsException) {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ElevenLabsException) {
            problem = "ElevenLabs refused: ${e.message}"
        } catch (e: Exception) {
            problem = "Couldn't reach ElevenLabs: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { repo.update(transform) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ElevenLabs voices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Studio-quality voices that speak every story language, with your own ElevenLabs API key. ElevenLabs " +
                "charges credits per character; a story is about 600 to 1,000 characters, and a story heard again is " +
                "free. Needs a connection: without one, or when the credits run out, an on-device voice reads on. " +
                "The text of the stories is sent to ElevenLabs.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (!hasKey) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("ElevenLabs API key") },
                placeholder = { Text("sk_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    keys.set(keyInput)
                    keyInput = ""
                },
                enabled = keyInput.isNotBlank(),
            ) { Text("Save key") }
            Text(
                "Create a key at elevenlabs.io, under Developers → API keys, with access to Text to Speech and " +
                    "Voices, and to User to see your credits here. It is only sent to the ElevenLabs API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("API key stored, encrypted on this phone.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = {
                    keys.set(null)
                    // Without a key the chosen ElevenLabs voice can't speak.
                    update { s ->
                        if (VoiceCatalog.elevenLabsVoice(s.voiceId) == null) s else s.copy(voiceId = VoiceCatalog.defaultVoiceId(s.language))
                    }
                },
            ) { Text("Remove key") }
        }
        usage?.let { Text(credits(it, ElevenLabsModel.byId(settings.elevenLabsModel)), style = MaterialTheme.typography.bodyMedium) }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }

        Text("Model", style = MaterialTheme.typography.titleSmall)
        ElevenLabsModel.entries.forEach { model ->
            ChoiceRow(model.label, model.description, selected = model.id == settings.elevenLabsModel) {
                update { it.copy(elevenLabsModel = model.id) }
            }
        }

        Text("Voice", style = MaterialTheme.typography.titleSmall)
        val list = voices
        when {
            list == null && problem == null -> Text("Loading your voices…", style = MaterialTheme.typography.bodySmall)
            list != null && list.isEmpty() -> Text("Your account has no voices yet; add some at elevenlabs.io.", style = MaterialTheme.typography.bodySmall)
            list != null -> list.sortedByDescending { language.code in it.languages }.forEach { voice ->
                val id = VoiceCatalog.ELEVENLABS_PREFIX + voice.id
                val verified = if (language.code in voice.languages) "Verified in ${language.name}" else null
                ChoiceRow(voice.name, listOfNotNull(voice.description.ifBlank { null }, verified).joinToString(" · "), selected = settings.voiceId == id) {
                    update { it.copy(voiceId = id, elevenLabsVoiceName = voice.name) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A typical story is about [STORY_CHARS] characters. */
private const val STORY_CHARS = 800

private fun credits(usage: CloudUsage, model: ElevenLabsModel): String {
    val number = NumberFormat.getIntegerInstance()
    val reset = usage.resetsAtUnix?.let {
        " Renews " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) + "."
    }.orEmpty()
    return "Credits: ${number.format(usage.left)} left of ${number.format(usage.limit)}, about ${number.format((usage.left / (STORY_CHARS * model.creditsPerChar)).toLong())} stories with ${model.label}.$reset"
}
