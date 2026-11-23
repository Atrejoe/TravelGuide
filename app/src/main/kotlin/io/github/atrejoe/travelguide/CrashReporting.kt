package io.github.atrejoe.travelguide

import android.content.Context
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Feedback
import io.sentry.protocol.SentryId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    /**
     * Sends a report about a story (Google Play's policy on AI-generated content): what the
     * listener says is wrong with it, and the story itself. Not a crash report, so it is sent
     * whether or not crash reports are on; when they are off, Sentry runs just for this and stops
     * again, without catching crashes. False when this build can't report or sending failed.
     */
    suspend fun report(context: Context, message: String, tags: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            if (!available) return@withContext false
            val started = !Sentry.isEnabled()
            if (started) init(context, catchCrashes = false)
            try {
                val id = Sentry.captureFeedback(Feedback(message.take(MAX_FEEDBACK)), null) { scope ->
                    tags.forEach { (key, value) -> scope.setTag(key, value.take(MAX_TAG)) }
                }
                Sentry.flush(FLUSH_MS)
                id != SentryId.EMPTY_ID
            } catch (e: RuntimeException) {
                false
            } finally {
                if (started && !_enabled.value) Sentry.close()
            }
        }

    private fun init(context: Context, catchCrashes: Boolean = true) {
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
            options.isEnableNdk = catchCrashes
            options.isEnableUncaughtExceptionHandler = catchCrashes
            options.isAnrEnabled = catchCrashes
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    private const val KEY = "send_crash_reports"
    private const val MAX_FEEDBACK = 4_000
    private const val MAX_TAG = 200
    private const val FLUSH_MS = 10_000L
}
