package com.adsamcik.streamferry.diagnostics

import android.content.Context
import com.adsamcik.streamferry.core.diagnostics.CrashReportStore
import com.adsamcik.streamferry.core.diagnostics.EventLogStore
import com.adsamcik.streamferry.core.redaction.LogRedactor
import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryQuery
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedLogRecord
import dev.tracebox.api.public
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One-time, bounded import. Originals are removed only after each selected batch is read back. */
object LegacyDiagnosticsMigration {
    private val LEGACY = LogTemplate.of("legacy|{}|{}|{}|{}")
    private const val MAX_RECORDS = 1_500
    private const val MAX_FILE_BYTES = 9L * 1024 * 1024
    private data class Entry(val kind: String, val build: String, val time: Long, val text: String) {
        val rendered: String get() = "legacy|$kind|$build|$time|$text"
    }

    suspend fun migrate(context: Context, handle: TraceboxHandle) {
        if (!handle.log.isEnabled(LogLevel.INFO)) return
        val eventDir = File(context.filesDir, "events")
        val crashDir = File(context.filesDir, "crashes")
        val files = (EventLogStore(eventDir).sessions() + CrashReportStore(crashDir).reports())
            .filter { it.length() <= MAX_FILE_BYTES && it.canonicalFile.parentFile == it.parentFile.canonicalFile }
            .sortedBy { it.lastModified() }
        if (files.isEmpty()) return
        val recent = ArrayDeque<Entry>()
        files.forEach { file ->
            currentCoroutineContext().ensureActive()
            val kind = if (file.parentFile == crashDir) "crash" else "event"
            val text = file.readText()
            val build = (if (kind == "crash") CrashReportStore.versionCodeOf(text) else EventLogStore.versionCodeOf(text))
                ?.toLongOrNull()?.toString() ?: "unknown"
            text.lineSequence().forEach { line ->
                LogRedactor.redact(line).codePoints().toArray().toList().chunked(160).forEach { part ->
                    recent.addLast(Entry(kind, build, file.lastModified(), String(part.toIntArray(), 0, part.size)))
                    while (recent.size > MAX_RECORDS) recent.removeFirst()
                }
            }
        }
        for (batch in recent.chunked(32)) {
            currentCoroutineContext().ensureActive()
            if (!handle.log.isEnabled(LogLevel.INFO)) return
            batch.forEach { entry ->
                handle.log.info(LEGACY, public(entry.kind), public(entry.build), public(entry.time), public(entry.text))
            }
            val stored = handle.recentDiagnostics(DiagnosticHistoryQuery(limit = 2_000, eventIds = setOf(GeneratedEventId.LOGRECORD)))
                as? DiagnosticHistory.Available ?: return
            val messages = stored.entries.mapNotNull { (it.record as? GeneratedLogRecord)?.rendered_message }.toSet()
            if (batch.any { it.rendered !in messages }) return // quota, policy or storage failure: keep originals
        }
        currentCoroutineContext().ensureActive()
        files.forEach { check(it.delete() || !it.exists()) { "Could not retire a legacy diagnostic file" } }
    }
}
