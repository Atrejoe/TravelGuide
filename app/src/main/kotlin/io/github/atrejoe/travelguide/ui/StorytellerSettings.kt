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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.DownloadStatus
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.R
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
    val resources = LocalResources.current
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
                    is DownloadStatus.DownloadStarted -> resources.getString(R.string.nano_downloading)
                    is DownloadStatus.DownloadProgress -> resources.getString(R.string.nano_downloading_mb, (status.totalBytesDownloaded / 1_000_000).toInt())
                    is DownloadStatus.DownloadFailed -> resources.getString(R.string.download_failed, status.e.message.orEmpty())
                    is DownloadStatus.DownloadCompleted -> null
                    else -> nanoProgress
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nanoProgress = resources.getString(R.string.download_failed, e.message.orEmpty())
        }
        nano = NanoStoryEnhancer.status()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.storyteller), style = MaterialTheme.typography.titleMedium)
        OptionRow(
            storytellerLabel(Storyteller.BUILT_IN),
            stringResource(R.string.storyteller_builtin_help),
            settings.storyteller == Storyteller.BUILT_IN,
            { choose(Storyteller.BUILT_IN) },
        )
        OptionRow(
            storytellerLabel(Storyteller.GEMINI_NANO),
            stringResource(R.string.storyteller_nano_help),
            settings.storyteller == Storyteller.GEMINI_NANO,
            { choose(Storyteller.GEMINI_NANO) },
        ) {
            val status = nanoProgress ?: when (nano) {
                null -> stringResource(R.string.nano_checking)
                NanoStoryEnhancer.Availability.AVAILABLE -> stringResource(R.string.nano_ready)
                NanoStoryEnhancer.Availability.DOWNLOADABLE -> stringResource(R.string.nano_downloadable)
                NanoStoryEnhancer.Availability.DOWNLOADING -> stringResource(R.string.nano_downloading_state)
                NanoStoryEnhancer.Availability.UNAVAILABLE -> stringResource(R.string.nano_unavailable)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (nano == NanoStoryEnhancer.Availability.DOWNLOADABLE && nanoProgress == null) {
                Button(onClick = { downloadNano() }) { Text(stringResource(R.string.nano_download)) }
            }
        }
        OptionRow(
            storytellerLabel(Storyteller.ON_DEVICE),
            stringResource(R.string.storyteller_open_help),
            settings.storyteller == Storyteller.ON_DEVICE,
            { choose(Storyteller.ON_DEVICE) },
        ) { OnDeviceSettings(settings) }
        OptionRow(
            storytellerLabel(Storyteller.CLAUDE),
            stringResource(R.string.storyteller_claude_help),
            settings.storyteller == Storyteller.CLAUDE,
            { choose(Storyteller.CLAUDE) },
        ) { ClaudeSettings(settings, spent) }
        Text(
            stringResource(R.string.storyteller_fallback),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the storyteller is called in the settings. */
@Composable
fun storytellerLabel(storyteller: Storyteller): String = stringResource(
    when (storyteller) {
        Storyteller.BUILT_IN -> R.string.storyteller_builtin
        Storyteller.GEMINI_NANO -> R.string.storyteller_nano
        Storyteller.ON_DEVICE -> R.string.storyteller_open
        Storyteller.CLAUDE -> R.string.storyteller_claude
    },
)

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
            Text(stringResource(R.string.key_stored), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { keys.set(null) }) { Text(stringResource(R.string.key_remove)) }
        }
    } else {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(R.string.key_anthropic)) },
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
        ) { Text(stringResource(R.string.key_save)) }
        Text(
            stringResource(R.string.key_anthropic_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(stringResource(R.string.model), style = MaterialTheme.typography.titleSmall)
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
                Text(stringResource(R.string.claude_per_place, dollars(perStory(model))), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    var budget by remember(settings.monthlyBudgetUsd) { mutableFloatStateOf(settings.monthlyBudgetUsd) }
    Text(
        stringResource(R.string.claude_budget, dollars(budget.toDouble()), dollars(spent)),
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

@Composable
private fun dollars(usd: Double): String = when {
    usd == 0.0 -> "$0"
    usd < 0.01 -> stringResource(R.string.less_than_cent)
    else -> String.format(Locale.ROOT, "$%.2f", usd)
}
