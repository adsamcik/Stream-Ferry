package com.adsamcik.streamferry.diagnostics

import android.app.Application
import com.adsamcik.streamferry.core.diagnostics.EventLogStore
import dev.tracebox.api.DiagnosticHistory
import dev.tracebox.api.DiagnosticHistoryEntry
import dev.tracebox.api.LogArgument
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedLogRecord
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LegacyDiagnosticsMigrationTest {
    @Test fun importsRedactedHistoryBeforeRetiringLegacyFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = EventLogStore(File(context.filesDir, "events")).writeSession("5999", "old", listOf("https://private.example/api?token=secret"))
        val handle = mockk<TraceboxHandle>(relaxed = true)
        val rendered = mutableListOf<String>()
        every { handle.log.isEnabled(any(), any()) } returns true
        every { handle.log.info(any(), *anyVararg()) } answers {
            val args = secondArg<Array<out LogArgument>>().map { PrivacyConfiguration.defaults().render(it).text }
            rendered += "legacy|" + args.joinToString("|")
        }
        every { handle.recentDiagnostics(any()) } answers {
            DiagnosticHistory.Available(rendered.map { text ->
                DiagnosticHistoryEntry(1, GeneratedLogRecord(2u, 0u, 0u, text, 0u, 0u, 0u, 0u))
            })
        }
        LegacyDiagnosticsMigration.migrate(context, handle)
        assertFalse(file.exists())
        assertTrue(rendered.any { it.startsWith("legacy|event|5999|") })
        assertFalse(rendered.joinToString().contains("private.example"))
        assertFalse(rendered.joinToString().contains("token=secret"))
    }

    @Test fun disabledOrUnavailableRecorderPreservesOriginals() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = EventLogStore(File(context.filesDir, "events")).writeSession("5999", "old", listOf("evidence"))
        val handle = mockk<TraceboxHandle>(relaxed = true)
        every { handle.log.isEnabled(any(), any()) } returns false
        LegacyDiagnosticsMigration.migrate(context, handle)
        assertTrue(file.exists())
        every { handle.log.isEnabled(any(), any()) } returns true
        every { handle.recentDiagnostics(any()) } returns DiagnosticHistory.Unavailable
        LegacyDiagnosticsMigration.migrate(context, handle)
        assertTrue(file.exists())
    }
}
