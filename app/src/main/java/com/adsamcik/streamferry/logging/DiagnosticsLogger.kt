package com.adsamcik.streamferry.logging

import android.util.Log
import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.logging.LogEntry
import com.adsamcik.streamferry.core.logging.LogLevel
import com.adsamcik.streamferry.source.api.DiagnosticSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The ONLY logging entry point in the app. Every message is passed through [LogRedactor] so tokens,
 * Jellyfin URLs, full proxy URLs, auth params and PlaySessionIds can never reach logcat or exports
 * (§13). In release builds, verbose/debug are dropped entirely.
 */
interface DiagnosticsLogger : DiagnosticSink {
    override fun d(tag: String, message: String)
    override fun i(tag: String, message: String)
    override fun w(tag: String, message: String, error: Throwable?)
    override fun e(tag: String, message: String, error: Throwable?)

    /** Append a structured, already-safe diagnostics entry for the redacted export. */
    override fun event(category: String, message: String)

    override fun debug(tag: String, message: String) = d(tag, message)
    override fun info(tag: String, message: String) = i(tag, message)
    override fun warn(tag: String, message: String, error: Throwable?) = w(tag, message, error)
    override fun error(tag: String, message: String, error: Throwable?) = e(tag, message, error)

    /**
     * Opt-in verbose trace (e.g. Cast/DLNA request/response traffic). Recorded — redacted — only while
     * [traceEnabled] is true, so it's off by default and never bloats the log unless the user asks for it.
     */
    override fun trace(tag: String, message: String)

    /** Gates [trace]; toggled by the opt-in "detailed TV communication tracing" setting. */
    var traceEnabled: Boolean

    /** Snapshot of recent redacted diagnostics for the opt-in export (§13). */
    fun exportRedacted(): List<String>

    /** Structured snapshot of recent entries, newest-last. All messages are already redacted. */
    fun entries(): List<LogEntry>

    fun clear()
}

/**
 * Default implementation. [debugBuild] controls whether d()/i() reach logcat; release drops them.
 */
class RedactingLogger(
    private val debugBuild: Boolean,
    private val maxEntries: Int = 1000,
) : DiagnosticsLogger {

    private val ring = ArrayDeque<LogEntry>()

    @Volatile
    override var traceEnabled: Boolean = false

    private fun record(level: LogLevel, tag: String, message: String, t: Throwable? = null) {
        val safe = LogRedactor.redact(message)
        val suffix = t?.let { " | ${LogRedactor.redact(it.javaClass.simpleName + ": " + it.message)}" } ?: ""
        val entry = LogEntry(
            timeMillis = System.currentTimeMillis(),
            level = level,
            category = tag,
            message = safe + suffix,
        )
        synchronized(ring) {
            ring.addLast(entry)
            while (ring.size > maxEntries) ring.removeFirst()
        }
    }

    override fun d(tag: String, message: String) {
        record(LogLevel.DEBUG, tag, message)
        if (debugBuild) Log.d(tag, LogRedactor.redact(message))
    }

    override fun i(tag: String, message: String) {
        record(LogLevel.INFO, tag, message)
        if (debugBuild) Log.i(tag, LogRedactor.redact(message))
    }

    override fun w(tag: String, message: String, t: Throwable?) {
        record(LogLevel.WARN, tag, message, t)
        Log.w(tag, LogRedactor.redact(message))
    }

    override fun e(tag: String, message: String, t: Throwable?) {
        record(LogLevel.ERROR, tag, message, t)
        // Never pass the raw throwable to Log.e (its stack/message may carry URLs); redact instead.
        Log.e(tag, LogRedactor.redact(message))
    }

    override fun event(category: String, message: String) = record(LogLevel.EVENT, category, message)

    override fun trace(tag: String, message: String) {
        if (!traceEnabled) return
        record(LogLevel.TRACE, tag, message)
        if (debugBuild) Log.d(tag, LogRedactor.redact(message))
    }

    override fun entries(): List<LogEntry> = synchronized(ring) { ring.toList() }

    override fun exportRedacted(): List<String> {
        val snapshot = synchronized(ring) { ring.toList() }
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return snapshot.map { e -> formatLogLine(e, fmt) }
    }

    override fun clear() = synchronized(ring) { ring.clear() }
}

/** One-line rendering of a redacted [LogEntry] for exports/persistence: `HH:mm:ss.SSS [LEVEL] cat: msg`. */
fun formatLogLine(e: LogEntry, fmt: SimpleDateFormat): String =
    "${fmt.format(Date(e.timeMillis))} [${e.level}] ${e.category}: ${e.message}"
