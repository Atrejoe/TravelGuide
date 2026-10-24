package io.github.atrejoe.travelguide.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.core.nav.RouteImportException
import io.github.atrejoe.travelguide.nav.NavigationListenerService
import io.github.atrejoe.travelguide.nav.NavigationState
import io.github.atrejoe.travelguide.nav.RouteImports
import io.github.atrejoe.travelguide.nav.RouteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings → Navigation: the planned route, GPX import and optional notification access, in a [SettingsSection]. */
@Composable
fun NavigationSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route by RouteRepository.route.collectAsStateWithLifecycle()
    val navigation by NavigationState.status.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var listenerEnabled by remember { mutableStateOf(NavigationListenerService.isEnabled(context)) }

    // Notification access is granted in the system settings; re-check when we come back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) listenerEnabled = NavigationListenerService.isEnabled(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val message = try {
                "Route imported: " + RouteImports.importUri(context, uri).summary()
            } catch (e: RouteImportException) {
                e.message ?: "Could not import the route."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not import the route: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importing = false
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Let the guide follow your navigation app live (notification access, below), or share a route " +
                "with Travel Guide, and the stories follow it instead of guessing the way ahead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val small = MaterialTheme.typography.bodySmall
        Text(
            "• Google Maps: plan directions, then Share. Google offers no route data, so the route is " +
                "recomputed with OpenStreetMap and may differ slightly.",
            style = small,
        )
        Text("• Komoot: share a tour link. Private tours: export the tour as GPX and share that file.", style = small)
        Text("• Garmin Connect / Explore: export the course as GPX (or FIT) and share or import the file.", style = small)
        Text("• Any other app (OsmAnd, …): share or import a GPX file.", style = small)

        Text(
            route?.let { "Current route: " + it.summary() } ?: "No route: the guide guesses the way ahead from your movement.",
            style = MaterialTheme.typography.bodyMedium,
        )
        route?.note?.let { Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !importing, onClick = { openDocument.launch(arrayOf("*/*")) }) {
                Text(if (importing) "Importing…" else "Import GPX file")
            }
            if (route != null) {
                OutlinedButton(onClick = { scope.launch { RouteRepository.clear(context) } }) { Text("Clear route") }
            }
        }

        Text("Navigation notifications (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "With notification access, the guide follows Google Maps, Komoot, Garmin, OsmAnd or Waze live: " +
                "it reads the next instruction (\"in 300 m turn left onto …\") and follows the road through " +
                "that turn, without sharing anything. It only looks at those apps' notifications and never " +
                "stores or sends them anywhere. Notifications only tell the next turn, so a shared route " +
                "still looks further ahead.",
            style = small,
        )
        Text(
            if (listenerEnabled) "Notification access: allowed" else "Notification access: not allowed",
            style = MaterialTheme.typography.bodyMedium,
        )
        navigation?.let { Text("Now: ${it.label()}", style = small) }
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "Notification access settings are not available on this device.", Toast.LENGTH_LONG).show()
            }
        }) {
            Text(if (listenerEnabled) "Change notification access" else "Enable navigation notifications")
        }
        if (!listenerEnabled) {
            Text(
                "If the switch is greyed out (Android 13+ for apps installed from an APK): open App info for " +
                    "Travel Guide, tap ⋮ → Allow restricted settings, then try again.",
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
