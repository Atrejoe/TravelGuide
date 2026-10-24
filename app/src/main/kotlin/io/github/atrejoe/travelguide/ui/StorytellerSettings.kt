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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import com.google.mlkit.genai.common.DownloadStatus
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.ClaudeModel
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.settings.Storyteller
import io.github.atrejoe.travelguide.story.NanoStoryEnhancer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Settings → Stories: who tells the stories (issue #7). Gemini Nano on the phone or Claude with
 * the listener's own API key can retell them; the built-in storyteller is the default.
 */
@Composable
fun StorytellerSettings(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    var spent by remember { mutableDoubleStateOf(0.0) }

    var nano by remember { mutableStateOf<NanoStoryEnhancer.Availability?>(null) }
    var nanoProgress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.storyteller) {
        spent = withContext(Dispatchers.IO) { context.app.retoldStories.spent(YearMonth.now(ZoneOffset.UTC).toString()) }
        if (settings.storyteller == Storyteller.GEMINI_NANO) nano = NanoStoryEnhancer.status()
    }

    fun choose(storyteller: Storyteller) = scope.launch { repo.update { it.copy(storyteller = storyteller) } }

    fun downloadNano() = scope.launch {
        try {
            NanoStoryEnhancer.download().collect { status ->
                nanoProgress = when (status) {
                    is DownloadStatus.DownloadStarted -> "Downloading Gemini Nano…"
                    is DownloadStatus.DownloadProgress -> "Downloading Gemini Nano: ${status.totalBytesDownloaded / 1_000_000} MB"
                    is DownloadStatus.DownloadFailed -> "Download failed: ${status.e.message}"
                    is DownloadStatus.DownloadCompleted -> null
                    else -> nanoProgress
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nanoProgress = "Download failed: ${e.message}"
        }
        nano = NanoStoryEnhancer.status()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Storyteller", style = MaterialTheme.typography.titleMedium)
        OptionRow(
            storytellerLabel(Storyteller.BUILT_IN),
            "Tells passages of the Wikipedia article as they are written. Free, instant and works everywhere, but " +
                "it can't retell: a story is only as well told as the article.",
            settings.storyteller == Storyteller.BUILT_IN,
            { choose(Storyteller.BUILT_IN) },
        )
        OptionRow(
            storytellerLabel(Storyteller.GEMINI_NANO),
            "Retells each story on the phone itself, through Android AICore. Free and private, but only on some " +
                "recent phones (e.g. Pixel 9 and later), and plainer than Claude.",
            settings.storyteller == Storyteller.GEMINI_NANO,
            { choose(Storyteller.GEMINI_NANO) },
        ) {
            val status = nanoProgress ?: when (nano) {
                null -> "Checking whether this phone has Gemini Nano…"
                NanoStoryEnhancer.Availability.AVAILABLE -> "Gemini Nano is ready."
                NanoStoryEnhancer.Availability.DOWNLOADABLE -> "Gemini Nano needs to be downloaded first."
                NanoStoryEnhancer.Availability.DOWNLOADING -> "Gemini Nano is being downloaded."
                NanoStoryEnhancer.Availability.UNAVAILABLE -> "This phone doesn't offer Gemini Nano: you'll hear the built-in stories."
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (nano == NanoStoryEnhancer.Availability.DOWNLOADABLE && nanoProgress == null) {
                Button(onClick = { downloadNano() }) { Text("Download Gemini Nano") }
            }
        }
        OptionRow(
            storytellerLabel(Storyteller.ON_DEVICE),
            "Retells each story with a small open model that the app runs itself. Free and private on any recent " +
                "phone, but it needs a download of about 1 GB, is slow on mid-range phones and less accurate. Experimental.",
            settings.storyteller == Storyteller.ON_DEVICE,
            { choose(Storyteller.ON_DEVICE) },
        ) { OnDeviceSettings(settings) }
        OptionRow(
            storytellerLabel(Storyteller.CLAUDE),
            "Retells each story the way a good guide would: the best told stories. Needs your own Anthropic API " +
                "key and a connection. Anthropic bills you per story, a place is paid for only once, and a monthly " +
                "budget caps the cost.",
            settings.storyteller == Storyteller.CLAUDE,
            { choose(Storyteller.CLAUDE) },
        ) { ClaudeSettings(settings, spent) }
        Text(
            "When a story can't be retold, you hear the built-in one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the storyteller is called in the settings. */
fun storytellerLabel(storyteller: Storyteller): String = when (storyteller) {
    Storyteller.BUILT_IN -> "Built-in"
    Storyteller.GEMINI_NANO -> "Gemini Nano, on this phone"
    Storyteller.ON_DEVICE -> "Open model, on this phone"
    Storyteller.CLAUDE -> "Claude, with your own key"
}

/** Claude's settings: the key, the model and the monthly budget. */
@Composable
private fun ClaudeSettings(settings: AppSettings, spent: Double) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    val keys = context.app.apiKeys
    val hasKey by keys.hasKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }

    if (hasKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("API key stored, encrypted on this phone.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { keys.set(null) }) { Text("Remove key") }
        }
    } else {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Anthropic API key") },
            placeholder = { Text("sk-ant-…") },
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
            "Create a key at console.anthropic.com. It is only sent to the Anthropic API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text("Model", style = MaterialTheme.typography.titleSmall)
    ClaudeModel.entries.forEach { model ->
        val selected = model.id == settings.claudeModel
        Row(
            Modifier.fillMaxWidth().selectable(
                selected = selected,
                onClick = { scope.launch { repo.update { it.copy(claudeModel = model.id) } } },
                role = Role.RadioButton,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(model.label, style = MaterialTheme.typography.bodyLarge)
                Text("About ${dollars(perStory(model))} per new place", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    var budget by remember(settings.monthlyBudgetUsd) { mutableFloatStateOf(settings.monthlyBudgetUsd) }
    Text(
        "Monthly budget: ${dollars(budget.toDouble())} · spent this month: ${dollars(spent)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = budget,
        onValueChange = { budget = it },
        onValueChangeFinished = { scope.launch { repo.update { it.copy(monthlyBudgetUsd = budget) } } },
        valueRange = 1f..20f,
        steps = 18,
    )
}

/** A typical story: the article's lead and the picked sentences in, a short story and some thinking out. */
private fun perStory(model: ClaudeModel): Double =
    (1_500 * model.inputUsdPerMTok + 600 * model.outputUsdPerMTok) / 1_000_000

private fun dollars(usd: Double): String = when {
    usd == 0.0 -> "$0"
    usd < 0.01 -> "less than $0.01"
    else -> String.format(Locale.ROOT, "$%.2f", usd)
}
