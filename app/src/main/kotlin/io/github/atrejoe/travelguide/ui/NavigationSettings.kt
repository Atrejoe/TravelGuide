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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrejoe.travelguide.R
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
                context.getString(R.string.route_imported, routeSummary(context, RouteImports.importUri(context, uri)))
            } catch (e: RouteImportException) {
                e.message ?: context.getString(R.string.route_import_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.route_import_failed_reason, e.message ?: e.javaClass.simpleName)
            } finally {
                importing = false
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.nav_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        val small = MaterialTheme.typography.bodySmall
        Text(stringResource(R.string.nav_google), style = small)
        Text(stringResource(R.string.nav_komoot), style = small)
        Text(stringResource(R.string.nav_garmin), style = small)
        Text(stringResource(R.string.nav_other), style = small)

        Text(
            route?.let { stringResource(R.string.nav_current_route, routeSummary(it)) } ?: stringResource(R.string.nav_no_route),
            style = MaterialTheme.typography.bodyMedium,
        )
        route?.note?.let { Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !importing, onClick = { openDocument.launch(arrayOf("*/*")) }) {
                Text(stringResource(if (importing) R.string.nav_importing else R.string.nav_import_gpx))
            }
            if (route != null) {
                OutlinedButton(onClick = { scope.launch { RouteRepository.clear(context) } }) { Text(stringResource(R.string.nav_clear_route)) }
            }
        }

        Text(stringResource(R.string.nav_notifications), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.nav_notifications_help),
            style = small,
        )
        Text(
            stringResource(if (listenerEnabled) R.string.nav_access_allowed else R.string.nav_access_denied),
            style = MaterialTheme.typography.bodyMedium,
        )
        navigation?.let { Text(stringResource(R.string.nav_now, navigationLabel(context, it)), style = small) }
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.nav_settings_unavailable), Toast.LENGTH_LONG).show()
            }
        }) {
            Text(stringResource(if (listenerEnabled) R.string.nav_change_access else R.string.nav_enable))
        }
        if (!listenerEnabled) {
            Text(
                stringResource(R.string.nav_restricted_help),
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
