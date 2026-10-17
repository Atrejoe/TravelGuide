package io.github.atrejoe.travelguide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.core.nav.RouteImportException
import io.github.atrejoe.travelguide.nav.RouteImports
import io.github.atrejoe.travelguide.service.GuideService
import kotlinx.coroutines.CancellationException

/**
 * Receives routes shared from other apps: Google Maps directions and Komoot tour links (as text),
 * and GPX/FIT files (e.g. Garmin or Komoot exports). Imports the route and confirms it.
 */
class ImportRouteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val request = requestFrom(intent)
        setContent {
            TravelGuideTheme {
                ImportRouteScreen(
                    request = request,
                    onClose = { finish() },
                    onStartGuide = {
                        startGuide()
                        finish()
                    },
                )
            }
        }
    }

    private fun startGuide() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            GuideService.start(this)
        } else {
            // The main screen asks for the location permission.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun requestFrom(intent: Intent?): ImportRequest? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { ImportRequest.File(it) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = listOfNotNull(
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                ).joinToString("\n").takeIf { it.isNotBlank() }
                when {
                    stream != null -> ImportRequest.File(stream)
                    text != null -> ImportRequest.Text(text)
                    else -> null
                }
            }
            else -> null
        }
    }
}

internal sealed interface ImportRequest {
    data class Text(val text: String) : ImportRequest
    data class File(val uri: Uri) : ImportRequest
}

@Composable
private fun ImportRouteScreen(request: ImportRequest?, onClose: () -> Unit, onStartGuide: () -> Unit) {
    val context = LocalContext.current
    var summary by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf(if (request == null) "Nothing to import was shared." else null) }

    LaunchedEffect(request) {
        if (request == null || summary != null || error != null) return@LaunchedEffect
        try {
            val route = when (request) {
                is ImportRequest.Text -> RouteImports.importText(context, request.text)
                is ImportRequest.File -> RouteImports.importUri(context, request.uri)
            }
            summary = route.summary()
            note = route.note
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouteImportException) {
            error = e.message
        } catch (e: Exception) {
            Log.w("ImportRoute", "Import failed", e)
            error = "Could not import the route: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val done = summary
                    val failed = error
                    when {
                        done != null -> {
                            Text("Route imported", style = MaterialTheme.typography.titleLarge)
                            Text(done, style = MaterialTheme.typography.bodyLarge)
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            val running = GuideStatus.state.value.running
                            Text(
                                if (running) {
                                    "The guide now follows this route and tells you about places along it."
                                } else {
                                    "Start guiding and the guide follows this route, telling you about places along it."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!running) Button(onClick = onStartGuide) { Text("Start guiding") }
                                OutlinedButton(onClick = onClose) { Text("Done") }
                            }
                        }
                        failed != null -> {
                            Text("Couldn't import the route", style = MaterialTheme.typography.titleLarge)
                            Text(failed, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = onClose) { Text("Close") }
                        }
                        else -> {
                            Text("Importing route…", style = MaterialTheme.typography.titleLarge)
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
