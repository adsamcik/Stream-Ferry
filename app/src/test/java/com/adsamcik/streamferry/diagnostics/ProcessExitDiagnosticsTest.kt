package com.adsamcik.streamferry.diagnostics

import android.app.ApplicationExitInfo
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ProcessExitDiagnosticsTest {
    @Test fun `CPU kill survives export with reason time and cached importance`() {
        val info = mockk<ApplicationExitInfo>(relaxed = true)
        every { info.reason } returns ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
        every { info.timestamp } returns 1000L
        every { info.pid } returns 31231
        every { info.importance } returns 400
        every { info.description } returns "excessive cpu api_key=secret-token"
        val text = ProcessExitDiagnostics.format(info)
        assertContains(text, "1970-01-01T00:00:01Z")
        assertContains(text, "pid=31231 reason=EXCESSIVE_RESOURCE_USAGE(9)")
        assertContains(text, "importance=400")
        assertContains(text, "excessive cpu")
        assertFalse(text.contains("secret-token"))
    }
}
