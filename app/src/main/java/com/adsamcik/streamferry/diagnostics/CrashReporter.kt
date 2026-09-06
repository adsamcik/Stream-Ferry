package com.adsamcik.streamferry.diagnostics

import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryQuery
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedExceptionRecord
import dev.tracebox.api.generated.GeneratedManagedCrash
import dev.tracebox.api.generated.GeneratedRecord

/** Local browser over Tracebox's durable store; Tracebox installs the only crash handler. */
class CrashReporter(private val handle: TraceboxHandle, private val installedAtMillis: Long) {
    private fun currentCrashes(): List<GeneratedRecord> =
        (handle.recentDiagnostics(DiagnosticHistoryQuery(
            sinceMillis = installedAtMillis,
            eventIds = setOf(GeneratedEventId.EXCEPTIONRECORD, GeneratedEventId.MANAGEDCRASH),
        )) as? DiagnosticHistory.Available)?.entries.orEmpty()
            .map { it.record }
            .filter { it is GeneratedManagedCrash || it is GeneratedExceptionRecord && it.kind == 1u }

    fun countForCurrentBuild(): Int = currentCrashes().let { records ->
        maxOf(records.count { it is GeneratedManagedCrash }, records.count { it is GeneratedExceptionRecord })
    }
    fun latestReportForCurrentBuild(): String? = currentCrashes().lastOrNull()?.let {
        when (it) {
            is GeneratedExceptionRecord -> "${it.exception_type}\n${it.stack_trace}"
            is GeneratedManagedCrash -> "Reduced fatal crash: exceptionCode=${it.primary_exception_code} flags=${it.flags}; detailed stack unavailable."
            else -> null
        }
    }
}
