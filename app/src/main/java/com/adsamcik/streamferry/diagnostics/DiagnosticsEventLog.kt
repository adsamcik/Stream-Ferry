package com.adsamcik.streamferry.diagnostics

import com.adsamcik.streamferry.core.diagnostics.EventLogStore
import com.adsamcik.streamferry.logging.LogEntry
import com.adsamcik.streamferry.logging.formatLogLine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the redacted diagnostics event log to app-private storage so a shared report survives app
 * restarts — the in-memory ring is wiped on process death, which is why a report generated in a fresh
 * launch (after the playbacks the user wanted to share) showed none of the playback/transcode/TV events.
 *
 * One file per app session (build-tagged via [EventLogStore]) so a shared report can include only the
 * CURRENT build's events. Everything persisted here is already redacted by the logger. App-private under
 * `filesDir/events`; not backed up (`allowBackup=false`) and cleared by "Delete all app data" (§13).
 */
class DiagnosticsEventLog(
    filesDir: File,
    private val versionCode: String,
    private val entriesSnapshot: () -> List<LogEntry>,
) {
    private val store = EventLogStore(File(filesDir, DIR))
    private val sessionStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    private val lineFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Skip a rewrite when nothing changed. Size alone is insufficient once the ring is full (size stays
    // at the cap while newest entries rotate), so also track the newest entry's timestamp.
    @Volatile private var lastSize = -1
    @Volatile private var lastTimeMillis = -1L

    /** Persist the current ring snapshot to this session's file. No-op when nothing has changed. */
    @Synchronized
    fun flush() {
        val entries = entriesSnapshot()
        val newestTime = entries.lastOrNull()?.timeMillis ?: 0L
        if (entries.size == lastSize && newestTime == lastTimeMillis) return
        val lines = entries.map { formatLogLine(it, lineFormat) }
        runCatching { store.writeSession(versionCode, sessionStamp, lines) }
            .onSuccess { lastSize = entries.size; lastTimeMillis = newestTime }
    }

    /**
     * Redacted events from the CURRENT build (prior sessions plus the current one), oldest first, bounded
     * to the newest [maxLines]. Flushes first so the current session's latest events are included.
     */
    fun exportForCurrentBuild(maxLines: Int = MAX_EXPORT_LINES): List<String> {
        flush()
        return store.linesForVersion(versionCode, maxLines)
    }

    /** Delete every persisted event log (Settings → Delete all app data). */
    fun clear() {
        store.clear()
        lastSize = -1
        lastTimeMillis = -1L
    }

    private companion object {
        const val DIR = "events"
        const val MAX_EXPORT_LINES = 2000
    }
}
