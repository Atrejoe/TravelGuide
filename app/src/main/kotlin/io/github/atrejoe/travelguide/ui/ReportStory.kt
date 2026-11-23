package io.github.atrejoe.travelguide.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import io.github.atrejoe.travelguide.BuildConfig
import io.github.atrejoe.travelguide.CrashReporting
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.StoryItem
import kotlinx.coroutines.launch

/** What is wrong with a story, as the listener reports it. */
enum class ReportReason(val label: Int, val tag: String) {
    OFFENSIVE(R.string.report_offensive, "offensive"),
    WRONG(R.string.report_wrong, "wrong"),
    OTHER(R.string.report_other, "other"),
}

/**
 * Lets the listener report a story to the developer from inside the app, as Google Play asks of
 * apps with AI-generated content: why, an optional comment, and the story itself. Never the
 * location. Sent through Sentry (see [CrashReporting.report]).
 */
@Composable
fun ReportStoryDialog(story: StoryItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf(ReportReason.OFFENSIVE) }
    var comment by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column {
                Text(stringResource(R.string.report_help), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    ReportReason.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = reason == option,
                                onClick = { reason = option },
                                role = Role.RadioButton,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == option, onClick = null)
                            Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(1_000) },
                    label = { Text(stringResource(R.string.report_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val sent = CrashReporting.report(context, reportMessage(story, reason, comment), reportTags(story, reason))
                        Toast.makeText(
                            context,
                            if (sent) R.string.report_sent else R.string.report_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                        sending = false
                        if (sent) onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.report_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The report as the developer reads it: in English, whatever the app's language. */
internal fun reportMessage(story: StoryItem, reason: ReportReason, comment: String): String = buildString {
    appendLine("Story reported as ${reason.tag}.")
    comment.trim().takeIf { it.isNotEmpty() }?.let { appendLine("Comment: $it") }
    appendLine()
    appendLine(story.title)
    story.toldBy?.let { appendLine(it) }
    appendLine(story.url)
    appendLine()
    append(story.text)
}

internal fun reportTags(story: StoryItem, reason: ReportReason): Map<String, String> = buildMap {
    put("report.reason", reason.tag)
    put("report.title", story.title)
    story.toldBy?.let { put("report.told_by", it) }
    put("report.url", story.url)
    put("app.version", BuildConfig.VERSION_NAME)
}
