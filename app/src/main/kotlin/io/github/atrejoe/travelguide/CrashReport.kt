package io.github.atrejoe.travelguide

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Remembers what the app was doing when it died, for crashes that happen without a debugger at
 * hand: a step written before risky work (such as running an open model in native code), a
 * Kotlin crash's stack trace, and, from Android 11, how Android ended the app (signal, memory).
 * Shown in the settings with a button to copy it.
 */
object CrashReport {
    private const val TAG = "CrashReport"
    private lateinit var dir: File
    private val stepFile get() = File(dir, "crash-step.txt")
    private val traceFile get() = File(dir, "crash-trace.txt")
    private val seenFile get() = File(dir, "crash-seen.txt")

    fun install(context: Context) {
        dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                traceFile.writeText("${Date()} on ${thread.name}:\n${e.stackTraceToString()}")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Marks risky work; when the app dies during [block], the next report names [step]. */
    suspend fun <T> during(step: String, block: suspend () -> T): T {
        CrashReporting.step(step)
        if (!::dir.isInitialized) return block()
        write(stepFile, step)
        try {
            return block()
        } finally {
            stepFile.delete()
        }
    }

    /** What went wrong last time, or null when the app ended normally since. */
    fun last(context: Context): String? {
        if (!::dir.isInitialized) return null
        val step = stepFile.takeIf { it.exists() }?.readText()
        val trace = traceFile.takeIf { it.exists() }?.readText()
        val exit = lastExit(context)
        if (step == null && trace == null) return null
        return buildString {
            step?.let { appendLine("The app stopped while: $it") }
            exit?.let { appendLine(it) }
            appendLine("App ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
            trace?.let { append(it.take(4_000)) }
        }.trim().also { Log.w(TAG, it) }
    }

    /** Forget the last crash, once it has been shown. */
    fun clear() {
        stepFile.delete()
        traceFile.delete()
        write(seenFile, System.currentTimeMillis().toString())
    }

    private fun lastExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val seen = seenFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        val info = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        if (info.timestamp <= seen) return null
        val how = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash, ${signal(info.status)}"
            ApplicationExitInfo.REASON_CRASH -> "crash in the app"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android stopped it for lack of memory"
            ApplicationExitInfo.REASON_SIGNALED -> "ended by ${signal(info.status)}"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too many resources"
            else -> "reason ${info.reason}"
        }
        val time = DateFormat.getDateTimeInstance().format(Date(info.timestamp))
        val memory = "memory ${info.pss / 1024} MB (PSS), ${info.rss / 1024} MB (RSS)"
        return "How: $how, at $time; $memory. ${info.description.orEmpty()}".trim()
    }

    private fun signal(number: Int): String = when (number) {
        4 -> "SIGILL (an instruction this processor doesn't have)"
        6 -> "SIGABRT (llama.cpp or the system stopped on an error)"
        7 -> "SIGBUS (bad memory access)"
        9 -> "SIGKILL (killed, usually for lack of memory)"
        11 -> "SIGSEGV (invalid memory access)"
        else -> "signal $number"
    }

    private fun write(file: File, text: String) {
        try {
            file.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write ${file.name}", e)
        }
    }
}
