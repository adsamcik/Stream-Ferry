package com.adsamcik.streamferry.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.adsamcik.streamferry.core.diagnostics.CrashReportFormatter
import com.adsamcik.streamferry.core.diagnostics.CrashReportStore
import com.adsamcik.streamferry.core.diagnostics.UncaughtCrashHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Captures uncaught exceptions on any thread, writes a **redacted** crash report to app-private
 * storage (`filesDir/crashes/`), then delegates to the previously-installed handler so the process
 * still terminates normally (the system crash dialog still shows; Play Console still records it).
 *
 * Reports are sandboxed app-private files (excluded from backup like all app data) and are removed by
 * "Delete all app data". The report text comes from the pure-JVM [CrashReportFormatter] (redacts
 * tokens / Jellyfin URLs / auth headers, §13); the file lifecycle is the pure-JVM [CrashReportStore].
 * This class is the thin Android glue: handler installation + `Build`/version metadata.
 */
class CrashReporter(
    context: Context,
    private val appVersion: String,
    private val versionCode: String,
    private val isDebug: Boolean,
) {
    private val appContext = context.applicationContext
    private val store = CrashReportStore(File(appContext.filesDir, DIR))
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /** Install as the process-wide uncaught-exception handler. Idempotent. */
    fun install() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        if (existing is UncaughtCrashHandler) return // already installed
        Thread.setDefaultUncaughtExceptionHandler(
            UncaughtCrashHandler(
                previous = existing,
                onCrash = { thread, throwable -> capture(thread, throwable) },
                terminate = {
                    // No prior handler (shouldn't happen on Android) — terminate the process ourselves.
                    Process.killProcess(Process.myPid())
                    exitProcess(CRASH_EXIT_CODE)
                },
            ),
        )
    }

    private fun capture(thread: Thread, throwable: Throwable) {
        val meta = CrashReportFormatter.Metadata(
            timeIso = isoFormat.format(Date()),
            appVersion = appVersion,
            versionCode = versionCode,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT.toString(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            threadName = thread.name,
            isDebug = isDebug,
        )
        val file = store.write(CrashReportFormatter.format(meta, throwable), fileFormat.format(Date()))
        // Debug builds: show the report immediately in a separate-process crash screen, then end this
        // dying process (so the system "app stopped" dialog doesn't also appear). Release builds skip
        // this and let the default handler terminate normally.
        if (isDebug && launchDebugCrashScreen(file)) {
            Process.killProcess(Process.myPid())
            exitProcess(CRASH_EXIT_CODE)
        }
    }

    /** Start the debug-only CrashActivity (in the `:crash` process). Returns false if it couldn't start. */
    private fun launchDebugCrashScreen(file: File): Boolean = runCatching {
        appContext.startActivity(
            Intent().apply {
                // CrashActivity lives in src/debug only; reference it by name so release never links it.
                setClassName(appContext, "com.adsamcik.streamferry.app.CrashActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("crashFilePath", file.absolutePath)
            },
        )
        true
    }.getOrDefault(false)

    fun count(): Int = store.count()

    /** Count of stored reports from the CURRENT build (what latest-build sharing would include). */
    fun countForCurrentBuild(): Int = store.countForVersion(versionCode)

    /** The most recent report's text, or null if there are none. */
    fun latestReport(): String? = store.latest()

    /** The most recent report from the CURRENT build, or null if this build has none. */
    fun latestReportForCurrentBuild(): String? = store.latestForVersion(versionCode)

    /** All stored reports concatenated (newest first), for sharing/export. */
    fun combinedReport(): String = store.combined()

    /**
     * Reports from the CURRENT build only, concatenated (newest first). Used for sharing so a shared
     * report contains only crashes relevant to the running build — older-build reports (from before an
     * update) are excluded.
     */
    fun combinedReportForCurrentBuild(): String = store.combinedForVersion(versionCode)

    /** Delete every stored crash report. */
    fun clear() = store.clear()

    private companion object {
        const val DIR = "crashes"
        const val CRASH_EXIT_CODE = 10
    }
}
