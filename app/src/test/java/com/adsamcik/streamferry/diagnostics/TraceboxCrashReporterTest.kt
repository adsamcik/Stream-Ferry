package com.adsamcik.streamferry.diagnostics

import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryEntry
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedExceptionRecord
import dev.tracebox.api.generated.GeneratedManagedCrash
import dev.tracebox.api.generated.GeneratedRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class TraceboxCrashReporterTest {
    private fun reporter(vararg records: GeneratedRecord): Pair<CrashReporter, TraceboxHandle> {
        val handle = mockk<TraceboxHandle>()
        every { handle.recentDiagnostics(any()) } returns DiagnosticHistory.Available(
            records.map { DiagnosticHistoryEntry(2_000, it) },
        )
        return CrashReporter(handle, 1_000) to handle
    }

    @Test fun summaryAndDetailedFatalAreOneCrashAndHandledErrorsDoNotAlert() {
        val fatal = GeneratedExceptionRecord(1u, "Failure", 1u, 1u, "Codec.decode:12", 1u)
        val handled = GeneratedExceptionRecord(0u, "Handled", 1u, 1u, "Codec.retry:20", 1u)
        val (reporter, handle) = reporter(GeneratedManagedCrash(42u, 1u, 1u, 0u), fatal, handled)
        assertEquals(1, reporter.countForCurrentBuild())
        assertEquals("Failure\nCodec.decode:12", reporter.latestReportForCurrentBuild())
        verify { handle.recentDiagnostics(match { it.sinceMillis == 1_000L }) }
    }

    @Test fun reducedOutOfMemoryCaptureStillAlertsWithoutADetailedRecord() {
        val (reporter, _) = reporter(GeneratedManagedCrash(42u, 0u, 0u, 1u))
        assertEquals(1, reporter.countForCurrentBuild())
        assertTrue(reporter.latestReportForCurrentBuild()!!.contains("detailed stack unavailable"))
    }
}
