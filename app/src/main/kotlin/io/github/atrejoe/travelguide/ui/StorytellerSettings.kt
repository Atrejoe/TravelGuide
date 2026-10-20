package io.github.atrejoe.travelguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.ClaudeModel
import io.github.atrejoe.travelguide.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Settings → Stories: optionally let Claude retell the stories with the listener's own API key
 * (issue #7). Off by default; the guide works the same without it.
 */
@Composable
fun StorytellerSettings(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    val keys = context.app.apiKeys
    val hasKey by keys.hasKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var spent by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(settings.claudeStories) {
        spent = withContext(Dispatchers.IO) { context.app.retoldStories.spent(YearMonth.now(ZoneOffset.UTC).toString()) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = settings.claudeStories,
                onValueChange = { on -> scope.launch { repo.update { it.copy(claudeStories = on) } } },
                role = Role.Switch,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Retell stories with Claude", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Optional: Claude retells each story from its Wikipedia article, with your own Anthropic API key. " +
                        "Anthropic bills you per story; a place is paid for only once. Without a key, over budget or " +
                        "when anything goes wrong, you hear the built-in story.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings.claudeStories, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
        }
        if (!settings.claudeStories) return@Column

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

        Text("Model", style = MaterialTheme.typography.titleMedium)
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
}

/** A typical story: the article's lead and the picked sentences in, a short story and some thinking out. */
private fun perStory(model: ClaudeModel): Double =
    (1_500 * model.inputUsdPerMTok + 600 * model.outputUsdPerMTok) / 1_000_000

private fun dollars(usd: Double): String = when {
    usd == 0.0 -> "$0"
    usd < 0.01 -> "less than $0.01"
    else -> String.format(Locale.ROOT, "$%.2f", usd)
}
