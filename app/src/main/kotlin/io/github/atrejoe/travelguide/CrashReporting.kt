package io.github.atrejoe.travelguide

import android.content.Context
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in crash reports to Sentry (issue #56), off by default. The DSN is the project's, the same
 * for everyone, and comes from the build (`SENTRY_DSN`); builds without one never report.
 *
 * Reports carry no personal data: no location, no place names, no screenshots, no tracing. What
 * the app was doing (see [CrashReport.during]) goes along as a breadcrumb.
 *
 * The choice is kept in plain SharedPreferences, not DataStore, because it is read synchronously
 * when the app starts: native crashes are only caught once Sentry runs.
 */
object CrashReporting {
    /** Whether this build can report at all. */
    val available: Boolean get() = BuildConfig.SENTRY_DSN.isNotEmpty()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun start(context: Context) {
        _enabled.value = available && prefs(context).getBoolean(KEY, false)
        if (_enabled.value) init(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        _enabled.value = available && on
        if (_enabled.value) init(context) else Sentry.close()
    }

    /** Adds what the app is about to do to the next report, if reporting is on. */
    fun step(step: String) {
        if (_enabled.value) Sentry.addBreadcrumb(step)
    }

    private fun init(context: Context) {
        if (Sentry.isEnabled()) return
        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "travelguide@${BuildConfig.VERSION_NAME}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.tracesSampleRate = null
            options.isEnableAutoSessionTracking = false
            // Native crashes, e.g. in llama.cpp, are the reason for this.
            options.isEnableNdk = true
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    private const val KEY = "send_crash_reports"
}
