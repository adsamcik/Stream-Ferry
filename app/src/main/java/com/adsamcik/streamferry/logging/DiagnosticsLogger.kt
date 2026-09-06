package com.adsamcik.streamferry.logging

import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.logging.LogEntry
import com.adsamcik.streamferry.core.logging.LogLevel
import com.adsamcik.streamferry.source.api.DiagnosticSink
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryQuery
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedLogRecord
import dev.tracebox.api.public
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Application facade; Tracebox owns capture, persistence, policy and Logcat mirroring. */
class TraceboxDiagnosticsLogger(
    private val handle: TraceboxHandle,
    private val versionCode: String,
) : DiagnosticsLogger {
    @Volatile override var traceEnabled = false

    private fun record(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val severity = when (level) {
            LogLevel.DEBUG -> dev.tracebox.api.LogLevel.DEBUG
            LogLevel.TRACE -> dev.tracebox.api.LogLevel.VERBOSE
            LogLevel.WARN -> dev.tracebox.api.LogLevel.WARN
            LogLevel.ERROR -> dev.tracebox.api.LogLevel.ERROR
            else -> dev.tracebox.api.LogLevel.INFO
        }
        if (handle.log.isEnabled(severity)) {
            val time = System.currentTimeMillis()
            val category = LogRedactor.redact(tag).replace('|', '_').take(64)
            // Redact before entering Tracebox. Split long protocol traces into bounded records
            // so Tracebox's 1 KiB message limit does not silently remove the response tail.
            LogRedactor.redact(message).codePoints().toArray().toList().chunked(160)
                .map { String(it.toIntArray(), 0, it.size) }.ifEmpty { listOf("") }.forEach { part ->
                handle.log.log(severity, EVENT, public(versionCode), public(time), public(level), public(category), public(part))
            }
        }
        // Tracebox stores structural frames and type, never the raw exception message.
        if (error != null) handle.crashes.record(error)
    }

    override fun d(tag: String, message: String) = record(LogLevel.DEBUG, tag, message)
    override fun i(tag: String, message: String) = record(LogLevel.INFO, tag, message)
    override fun w(tag: String, message: String, error: Throwable?) = record(LogLevel.WARN, tag, message, error)
    override fun e(tag: String, message: String, error: Throwable?) = record(LogLevel.ERROR, tag, message, error)
    override fun event(category: String, message: String) = record(LogLevel.EVENT, category, message)
    override fun trace(tag: String, message: String) {
        if (traceEnabled) record(LogLevel.TRACE, tag, message)
    }

    override fun entries(): List<LogEntry> {
        val history = handle.recentDiagnostics(DiagnosticHistoryQuery(limit = 2_000, eventIds = setOf(GeneratedEventId.LOGRECORD)))
        return (history as? DiagnosticHistory.Available)?.entries.orEmpty().mapNotNull { entry ->
            decode((entry.record as? GeneratedLogRecord)?.rendered_message ?: return@mapNotNull null, versionCode)
        }
    }

    override fun exportRedacted(): List<String> {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return entries().map { formatLogLine(it, format) }
    }

    override fun clear() {
        check(handle.delete(DeleteRequest.ALL_TRACEBOX_DATA) == DeleteReport.COMPLETE) {
            "Tracebox could not complete diagnostics deletion"
        }
    }

    companion object {
        private val EVENT = LogTemplate.of("sf|{}|{}|{}|{}|{}")

        internal fun decode(text: String, versionCode: String): LogEntry? {
            val fields = text.split('|', limit = 6)
            if (fields.size != 6 || fields[0] != "sf" || fields[1] != versionCode) return null
            val time = fields[2].toLongOrNull() ?: return null
            val level = LogLevel.entries.firstOrNull { it.name == fields[3] } ?: return null
            return LogEntry(time, level, fields[4], fields[5])
        }
    }
}

fun formatLogLine(e: LogEntry, fmt: SimpleDateFormat): String =
    "${fmt.format(Date(e.timeMillis))} [${e.level}] ${e.category}: ${e.message}"
