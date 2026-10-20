package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.ui.compose.TraceboxDiagnosticsScreen
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiConfiguration

/** Uses the host's expressive theme and keeps Tracebox's review and operation ownership intact. */
@Composable
fun DiagnosticsControlsScreen(
    handle: TraceboxHandle,
    configuration: TraceboxDiagnosticsUiConfiguration,
    modifier: Modifier = Modifier,
) {
    val controls = remember(handle, configuration.defaultPolicy) {
        DiagnosticsControlsHandle(handle, configuration.defaultPolicy)
    }
    TraceboxDiagnosticsScreen(controls, modifier, configuration)
}

/** Re-enabling Tracebox's reset policy must resume logging, not retain its OFF log threshold. */
internal class DiagnosticsControlsHandle(
    private val delegate: TraceboxHandle,
    private val defaults: TraceboxPolicy,
) : TraceboxHandle by delegate {
    override fun updatePolicy(policy: TraceboxPolicy): PolicyUpdateResult {
        val reenableResetPolicy = delegate.policy.value == TraceboxPolicy.disabled() && policy.enabled
        return delegate.updatePolicy(if (reenableResetPolicy) defaults.copy(enabled = true) else policy)
    }
}
