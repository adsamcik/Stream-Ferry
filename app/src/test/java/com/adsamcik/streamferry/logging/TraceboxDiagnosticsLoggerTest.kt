package com.adsamcik.streamferry.logging

import com.adsamcik.streamferry.core.logging.LogLevel
import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryEntry
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.generated.GeneratedLogRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class TraceboxDiagnosticsLoggerTest {
    private val messages = mutableListOf<String>()
    private var enabled = true
    private val handle = mockk<TraceboxHandle>(relaxed = true)
    private val sink = object : TraceboxLogger {
        override fun isEnabled(level: dev.tracebox.api.LogLevel, category: LogCategory) = enabled
        override fun log(level: dev.tracebox.api.LogLevel, template: LogTemplate, vararg arguments: LogArgument) {
            var rendered = template.value
            arguments.forEach { rendered = rendered.replaceFirst("{}", PrivacyConfiguration.defaults().render(it).text) }
            assertTrue(rendered.toByteArray().size <= 1_024)
            messages += rendered
        }
        override fun error(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument) = Unit
        override fun performanceStart(template: LogTemplate, vararg arguments: LogArgument): PerformanceMeasurement = error("unused")
    }
    private fun logger(): TraceboxDiagnosticsLogger {
        every { handle.log } returns sink
        every { handle.recentDiagnostics(any()) } answers {
            DiagnosticHistory.Available(messages.map { text ->
                DiagnosticHistoryEntry(42, GeneratedLogRecord(2u, 0u, 0u, text, 0u, 0u, 0u, 0u))
            })
        }
        return TraceboxDiagnosticsLogger(handle, "6000")
    }

    @Test fun traceRequiresOptInAndLibraryPolicy() {
        val logger = logger()
        logger.trace("dlna", "hidden")
        assertTrue(messages.isEmpty())
        logger.traceEnabled = true
        logger.trace("dlna", "shown")
        assertEquals(LogLevel.TRACE, logger.entries().single().level)
        enabled = false
        logger.trace("dlna", "disabled")
        assertEquals(1, messages.size)
    }

    @Test fun redactsBeforeTraceboxAndCapturesThrowableStructurally() {
        val logger = logger()
        val failure = IllegalStateException("exception-secret")
        logger.e("network", "GET https://private.example/media?api_key=token-secret", failure)
        val text = messages.joinToString()
        assertFalse(text.contains("private.example"))
        assertFalse(text.contains("token-secret"))
        assertFalse(text.contains("exception-secret"))
        verify(exactly = 1) { handle.crashes.record(failure) }
    }

    @Test fun longUnicodeAndDelimiterRichTracesSurviveBoundedRecordsAndRestartReads() {
        val logger = logger()
        val message = "😀|ě".repeat(350)
        logger.event("playback", message)
        assertEquals(message, logger.entries().joinToString("") { it.message })
        assertEquals(message, TraceboxDiagnosticsLogger(handle, "6000").entries().joinToString("") { it.message })
        assertTrue(TraceboxDiagnosticsLogger(handle, "5999").entries().isEmpty())
        assertNull(TraceboxDiagnosticsLogger.decode("sf|6000|not-time|INFO|test|text", "6000"))
    }
}
