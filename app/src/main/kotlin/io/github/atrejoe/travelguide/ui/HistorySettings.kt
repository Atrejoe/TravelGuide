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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.guide.HistoryRetention
import io.github.atrejoe.travelguide.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the history keeps for, in the settings. */
@Composable
fun retentionLabel(retention: HistoryRetention): String = stringResource(
    when (retention) {
        HistoryRetention.TRIP -> R.string.retention_trip
        HistoryRetention.WEEK -> R.string.retention_week
        HistoryRetention.MONTH -> R.string.retention_month
        HistoryRetention.THREE_MONTHS -> R.string.retention_three_months
        HistoryRetention.YEAR -> R.string.retention_year
        HistoryRetention.ALWAYS -> R.string.retention_always
    },
)

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
        stringResource(R.string.history_help),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.history_remember_for), style = MaterialTheme.typography.titleSmall)
    HistoryRetention.entries.forEach { option ->
        OptionRow(
            retentionLabel(option),
            when (option) {
                HistoryRetention.TRIP -> stringResource(R.string.retention_trip_help)
                HistoryRetention.ALWAYS -> stringResource(R.string.retention_always_help)
                else -> stringResource(R.string.retention_other_help)
            },
            selected = option == retention,
            onSelect = { scope.launch { context.app.settings.update { it.copy(historyRetention = option.name) } } },
        )
    }
    OutlinedButton(
        onClick = { confirming = true },
        enabled = count > 0,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.history_erase)) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.history_erase_title)) },
            text = { Text(stringResource(R.string.history_erase_text, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        withContext(Dispatchers.IO) { history.clear() }
                        GuideStatus.update { it.copy(history = listOfNotNull(it.current)) }
                    }
                }) { Text(stringResource(R.string.erase)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
