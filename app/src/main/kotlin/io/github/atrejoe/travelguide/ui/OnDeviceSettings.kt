package io.github.atrejoe.travelguide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.CrashReport
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.LocalModel
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.story.LocalStoryBenchmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Stories → Open model: which model, its download, and how fast it is here (issue #51). */
@Composable
fun OnDeviceSettings(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.app.settings
    val models = context.app.localModels
    val installed by models.installed.collectAsStateWithLifecycle()
    val progress by models.progress.collectAsStateWithLifecycle()
    var benchmark by remember { mutableStateOf<String?>(null) }
    var benchmarkJob by remember { mutableStateOf<Job?>(null) }
    val selected = LocalModels.byId(settings.localModel)

    if (!LocalStoryBenchmark.supported) {
        Text(stringResource(R.string.open_needs_arm), style = MaterialTheme.typography.bodyMedium)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.model), style = MaterialTheme.typography.titleMedium)
        LocalModels.all.forEach { model ->
            val isSelected = model.id == selected.id
            val downloading = progress[model.id]
            Column {
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = isSelected,
                        onClick = { scope.launch { repo.update { it.copy(localModel = model.id) } } },
                        role = Role.RadioButton,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(stringResource(R.string.model_size, model.name, model.sizeMb), style = MaterialTheme.typography.bodyLarge)
                        Text(localModelDescription(model), style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        downloading != null -> {}
                        model.id in installed -> OutlinedButton(onClick = { models.delete(model) }) { Text(stringResource(R.string.delete)) }
                        else -> OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    models.download(model)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, context.getString(R.string.download_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text(stringResource(R.string.download)) }
                    }
                }
                if (downloading != null) {
                    if (downloading < 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { downloading }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (selected.id !in installed) {
            Text(
                stringResource(R.string.open_download_first, selected.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Button(
            onClick = {
                val running = benchmarkJob
                if (running != null) {
                    running.cancel()
                    return@Button
                }
                benchmarkJob = scope.launch {
                    benchmark = context.getString(R.string.open_loading, selected.name)
                    try {
                        benchmark = LocalStoryBenchmark.run(context, selected) { benchmark = it }
                    } catch (e: CancellationException) {
                        benchmark = null
                        throw e
                    } catch (e: Exception) {
                        benchmark = context.getString(R.string.open_cannot_run, selected.name, e.message.orEmpty())
                    } finally {
                        benchmarkJob = null
                    }
                }
            },
        ) { Text(stringResource(if (benchmarkJob != null) R.string.stop else R.string.measure_speed)) }
        Text(
            stringResource(R.string.measure_speed_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        benchmark?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** What went wrong when the app last crashed (see [CrashReport]), with a button to copy it. */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { CrashReport.last(context) } }
    val text = report ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.crashed_last_time), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("TravelGuide crash", text))
                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
            OutlinedButton(onClick = {
                CrashReport.clear()
                report = null
            }) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

/** What an open model is like, in the app's language. */
@Composable
private fun localModelDescription(model: LocalModel): String = when (model.id) {
    "qwen3-1.7b-q4km" -> stringResource(R.string.local_model_qwen)
    "gemma3-1b-q4km" -> stringResource(R.string.local_model_gemma)
    "smollm2-1.7b-q4km" -> stringResource(R.string.local_model_smollm)
    else -> model.description
}
