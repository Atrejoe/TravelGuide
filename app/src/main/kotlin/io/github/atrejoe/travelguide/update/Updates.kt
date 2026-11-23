package io.github.atrejoe.travelguide.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.atrejoe.travelguide.BuildConfig
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.core.update.AppVersion
import io.github.atrejoe.travelguide.core.update.GitHubReleases
import io.github.atrejoe.travelguide.core.update.Release
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import io.github.atrejoe.travelguide.settings.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tells people who installed the app from GitHub that a newer release is out: once a day the app
 * asks GitHub for its releases, and a newer one shows as a card on the guide screen and, once per
 * release, as a notification that opens its release page. Stable versions are offered stable
 * releases; alpha versions every release.
 *
 * Installs from Google Play get their updates from Play and never check; nor do builds of pull
 * requests and manual runs, which have no place in the order of releases. The app never installs
 * anything itself: the listener downloads the APK from the release page.
 */
object Updates {
    private const val TAG = "Updates"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val PLAY_STORE = "com.android.vending"

    private val _available = MutableStateFlow<Release?>(null)

    /** A newer release the listener hasn't put aside, or null. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val current: AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME)
    private val checking = Mutex()

    /** Whether this install looks for updates on GitHub at all; see the class comment. */
    fun applies(context: Context): Boolean = current != null && installer(context) != PLAY_STORE

    /** Shows what an earlier check found, then checks again if it's been a day. */
    suspend fun start(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, true)
        if (!applies(context) || !_enabled.value) return
        _available.value = remembered(context)
        check(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        if (!on) {
            _available.value = null
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    /** Puts the offered release aside until a newer one comes out. */
    fun dismiss(context: Context) {
        val release = _available.value ?: return
        prefs(context).edit { putString(KEY_DISMISSED, release.tag) }
        _available.value = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Asks GitHub for the releases, at most once a day unless [force]d. */
    suspend fun check(context: Context, force: Boolean = false) = checking.withLock {
        val version = current ?: return@withLock
        val prefs = prefs(context)
        if (!applies(context) || !prefs.getBoolean(KEY_ENABLED, true)) return@withLock
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < DAY_MS) return@withLock
        val releases = try {
            GitHubReleases(UrlConnectionFetcher()).latest()
        } catch (e: Exception) {
            // Offline, or GitHub's limit for unsigned-in requests: try again next time.
            Log.i(TAG, "Couldn't check for updates: ${e.message}")
            return@withLock
        }
        val newer = GitHubReleases.newer(version, releases)
        prefs.edit {
            putLong(KEY_CHECKED, now)
            putString(KEY_TAG, newer?.tag)
            putString(KEY_URL, newer?.url)
        }
        _available.value = newer?.takeIf { it.tag != prefs.getString(KEY_DISMISSED, null) }
        if (newer != null && newer.tag != prefs.getString(KEY_NOTIFIED, null) && notify(context, newer)) {
            prefs.edit { putString(KEY_NOTIFIED, newer.tag) }
        }
    }

    private fun remembered(context: Context): Release? {
        val prefs = prefs(context)
        val tag = prefs.getString(KEY_TAG, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        val version = AppVersion.parse(tag) ?: return null
        // After updating, the remembered release is no longer newer.
        if (current == null || version <= current || tag == prefs.getString(KEY_DISMISSED, null)) return null
        return Release(version, tag, url)
    }

    /** Posts the notification; false when notifications aren't allowed. */
    private fun notify(appContext: Context, release: Release): Boolean {
        // In the app's language (issue #92), which the application context doesn't follow.
        val context = AppLanguage.wrap(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_updates_description)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, release.url.toUri()),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available, release.version.toString()))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION") // getInstallerPackageName before Android 11.
    private fun installer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private const val KEY_ENABLED = "check"
    private const val KEY_CHECKED = "checked_at"
    private const val KEY_TAG = "newer_tag"
    private const val KEY_URL = "newer_url"
    private const val KEY_DISMISSED = "dismissed_tag"
    private const val KEY_NOTIFIED = "notified_tag"
}
