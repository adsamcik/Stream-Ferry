package com.adsamcik.streamferry.ui.screens

import dev.tracebox.api.CaptureKind
import dev.tracebox.api.LogLevel
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsControlsHandleTest {
    private val defaults = TraceboxPolicy(
        minimumLogLevel = LogLevel.VERBOSE,
        captures = setOf(CaptureKind.JVM_CRASH, CaptureKind.HANDLED_EXCEPTION, CaptureKind.ANR, CaptureKind.OS_EXIT),
    )

    @Test
    fun enablingResetRecorderRestoresLoggingAndOnlySupportedCaptureSources() {
        val policy = MutableStateFlow(TraceboxPolicy.disabled())
        val controls = controls(policy)

        assertEquals(PolicyUpdateResult.SUCCESS, controls.updatePolicy(policy.value.copy(enabled = true)))

        assertEquals(defaults, policy.value)
        assertTrue(policy.value.accepts(LogLevel.INFO))
        assertFalse(CaptureKind.NATIVE_CRASH in policy.value.captures)
    }

    @Test
    fun ordinaryDisableAndEnablePreservesCustomRecordingSettings() {
        val custom = defaults.copy(
            minimumLogLevel = LogLevel.WARN,
            mirrorToLogcat = true,
            performanceLoggingEnabled = true,
            minimumPerformanceDurationNanos = 10_000_000,
            captures = setOf(CaptureKind.JVM_CRASH),
        )
        val policy = MutableStateFlow(custom)
        val controls = controls(policy)

        controls.updatePolicy(policy.value.copy(enabled = false))
        assertFalse(policy.value.accepts(LogLevel.ERROR))
        controls.updatePolicy(policy.value.copy(enabled = true))

        assertEquals(custom, policy.value)
    }

    @Test
    fun deliberatelyCrashOnlyPolicyIsNotReplacedByLoggingDefaults() {
        val crashOnly = defaults.copy(minimumLogLevel = LogLevel.OFF, captures = setOf(CaptureKind.JVM_CRASH))
        val policy = MutableStateFlow(crashOnly.copy(enabled = false))

        controls(policy).updatePolicy(policy.value.copy(enabled = true))

        assertEquals(crashOnly, policy.value)
    }

    @Test
    fun failedEnableKeepsRecorderDisabledAndReturnsFailure() {
        val policy = MutableStateFlow(TraceboxPolicy.disabled())

        val result = controls(policy, PolicyUpdateResult.FAILED).updatePolicy(policy.value.copy(enabled = true))

        assertEquals(PolicyUpdateResult.FAILED, result)
        assertFalse(policy.value.enabled)
    }

    private fun controls(
        policy: MutableStateFlow<TraceboxPolicy>,
        result: PolicyUpdateResult = PolicyUpdateResult.SUCCESS,
    ): DiagnosticsControlsHandle {
        val handle = mockk<TraceboxHandle>()
        every { handle.policy } returns policy
        every { handle.updatePolicy(any()) } answers {
            if (result == PolicyUpdateResult.SUCCESS) policy.value = firstArg()
            result
        }
        return DiagnosticsControlsHandle(handle, defaults)
    }
}
