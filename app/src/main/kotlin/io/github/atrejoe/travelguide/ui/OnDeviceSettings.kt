package io.github.atrejoe.travelguide.ui

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
import androidx.compose.runtime.getValue
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
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.story.LocalStoryBenchmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
        Text("Open models need a 64-bit ARM phone; this one isn't.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model", style = MaterialTheme.typography.titleMedium)
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
                        Text("${model.name} · ${model.sizeMb} MB", style = MaterialTheme.typography.bodyLarge)
                        Text(model.description, style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        downloading != null -> {}
                        model.id in installed -> OutlinedButton(onClick = { models.delete(model) }) { Text("Delete") }
                        else -> OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    models.download(model)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("Download") }
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
                "Download ${selected.name} to use it. Until then you hear the built-in stories.",
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
                    benchmark = "Loading ${selected.name}…"
                    try {
                        benchmark = LocalStoryBenchmark.run(context, selected) { benchmark = it }
                    } catch (e: CancellationException) {
                        benchmark = null
                        throw e
                    } catch (e: Exception) {
                        benchmark = "${selected.name} could not run here: ${e.message}"
                    } finally {
                        benchmarkJob = null
                    }
                }
            },
        ) { Text(if (benchmarkJob != null) "Stop" else "Measure speed") }
        Text(
            "Retells a sample story about the Grote Kerk in Haarlem and shows how fast this phone is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        benchmark?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
