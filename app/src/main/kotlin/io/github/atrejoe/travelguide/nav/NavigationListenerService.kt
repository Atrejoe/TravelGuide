package io.github.atrejoe.travelguide.nav

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.atrejoe.travelguide.core.nav.NavigationNotifications
import io.github.atrejoe.travelguide.core.nav.NavigationStatus
import io.github.atrejoe.travelguide.core.nav.RouteOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Optional: watches the notifications of known navigation apps (Google Maps, Komoot, Garmin,
 * OsmAnd, Waze) to show "navigating with …" and the next instruction, to let the guide follow that
 * instruction live along the road, and to drop a shared route when navigation in that app ends.
 * Notifications of other apps are ignored without being read.
 *
 * Notifications don't contain the route itself, only the next turn; sharing the route gives the
 * whole of it.
 */
class NavigationListenerService : NotificationListenerService() {

    /** Active navigation notifications by notification key. */
    private val active = LinkedHashMap<String, NavigationStatus>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onListenerConnected() {
        super.onListenerConnected()
        active.clear()
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read active notifications", e)
        }
        publish()
    }

    override fun onListenerDisconnected() {
        active.clear()
        publish()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: RuntimeException) {
            // Never let an unexpected notification crash the listener.
            Log.w(TAG, "Ignoring a notification from ${sbn.packageName}", e)
        }
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val removed = active.remove(sbn.key) ?: return
        publish()
        scheduleEndCheck(removed.app.origin)
    }

    override fun onDestroy() {
        scope.cancel()
        active.clear()
        NavigationState.update(null)
        super.onDestroy()
    }

    private fun handle(sbn: StatusBarNotification) {
        // Only known navigation apps are looked at.
        if (sbn.packageName !in NavigationNotifications.apps) return
        val notification: Notification = sbn.notification ?: return
        val extras = notification.extras
        val status = NavigationNotifications.parse(
            packageName = sbn.packageName,
            category = notification.category,
            ongoing = sbn.isOngoing,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT),
            subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT),
            receivedAtMillis = System.currentTimeMillis(),
        )
        if (status != null) {
            active[sbn.key] = status
        } else {
            active.remove(sbn.key)?.let { scheduleEndCheck(it.app.origin) }
        }
    }

    private fun publish() {
        NavigationState.update(active.values.lastOrNull())
    }

    /**
     * When an app's navigation notification is gone for a while (not just replaced while
     * re-routing), its navigation has ended: drop a route that came from that app, so the guide
     * goes back to guessing the way ahead.
     */
    private fun scheduleEndCheck(origin: RouteOrigin) {
        scope.launch {
            delay(END_GRACE_MILLIS)
            if (active.values.any { it.app.origin == origin }) return@launch
            val route = RouteRepository.route.value ?: return@launch
            if (route.origin == origin) {
                Log.i(TAG, "Navigation in ${origin.label} ended; dropping its route")
                RouteRepository.clear(applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "NavigationListener"
        private const val END_GRACE_MILLIS = 15_000L

        /** Whether the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
