package io.github.atrejoe.travelguide.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.guide.HistoryRetention
import io.github.atrejoe.travelguide.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the history keeps for, in the settings. */
fun retentionLabel(retention: HistoryRetention): String = when (retention) {
    HistoryRetention.TRIP -> "Only during the trip"
    HistoryRetention.WEEK -> "A week"
    HistoryRetention.MONTH -> "A month"
    HistoryRetention.THREE_MONTHS -> "Three months"
    HistoryRetention.YEAR -> "A year"
    HistoryRetention.ALWAYS -> "Always"
}

/** Settings → Story history (issue #84): how long told stories are remembered, and erasing them. */
@Composable
fun HistorySettings(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = context.app.storyHistory
    val count by history.count.collectAsStateWithLifecycle()
    val retention = HistoryRetention.byName(settings.historyRetention)
    var confirming by remember { mutableStateOf(false) }

    Text(
        "The guide remembers the stories it told, so it doesn't tell them again on a later trip, and lists " +
            "them on the guide screen to play again. They stay on this phone.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Remember stories for", style = MaterialTheme.typography.titleSmall)
    HistoryRetention.entries.forEach { option ->
        OptionRow(
            retentionLabel(option),
            when (option) {
                HistoryRetention.TRIP -> "Nothing is kept: after a restart, the same stories can come again."
                HistoryRetention.ALWAYS -> "Until you erase the history."
                else -> "Then a story may be told again."
            },
            selected = option == retention,
            onSelect = { scope.launch { context.app.settings.update { it.copy(historyRetention = option.name) } } },
        )
    }
    OutlinedButton(
        onClick = { confirming = true },
        enabled = count > 0,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Erase history") }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase the story history?") },
            text = { Text("The $count stories remembered are forgotten, and the guide may tell them again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        withContext(Dispatchers.IO) { history.clear() }
                        GuideStatus.update { it.copy(history = listOfNotNull(it.current)) }
                    }
                }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
