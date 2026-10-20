package com.adsamcik.streamferry.app

import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tracebox.api.LogLevel
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.TraceboxHealth
import com.adsamcik.streamferry.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the existing API 37 test device; exercises the shipped SDK UI and actual recorder. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsControlsTest {
    @Test
    fun resetRecorderCanBeEnabledThroughItsVisibleControlAndPrepareExport() {
        val app = ApplicationProvider.getApplicationContext<StreamFerryApplication>()
        val handle = app.tracebox
        await { handle.health.value != TraceboxHealth.INITIALIZING }
        val previous = handle.policy.value
        var failure: Throwable? = null
        try {
            val disabled = handle.updatePolicy(TraceboxPolicy.disabled())
            assertEquals("Could not establish disabled recorder: ${handle.health.value}", PolicyUpdateResult.SUCCESS, disabled)
            ActivityScenario.launch(DiagnosticsActivity::class.java).use {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                var control: AccessibilityNodeInfo? = null
                await({ "Recorder policy=${handle.policy.value}, health=${handle.health.value}" }) {
                    control = automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("Diagnostics")
                        ?.firstOrNull { node -> node.text?.toString() == "Diagnostics" }
                    control != null
                }
                while (control?.isCheckable != true) {
                    control = control?.parent ?: error("Diagnostics has no switch semantics")
                }
                assertTrue("Diagnostics switch does not accept clicks", control!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await({ "Recorder policy=${handle.policy.value}, health=${handle.health.value}" }) {
                    handle.health.value == TraceboxHealth.READY &&
                        handle.policy.value.enabled && handle.log.isEnabled(LogLevel.INFO)
                }
                assertEquals(app.diagnosticsUiConfiguration.defaultPolicy, handle.policy.value)
                app.logger.event("diagnostics", "Export regression test")
                val prepared = handle.packages.prepare(PackageRequest.STANDARD)
                assertTrue("Enabled recorder could not prepare export: $prepared, ${handle.health.value}",
                    prepared is PackagePreparationResult.Ready)
                val preview = (prepared as PackagePreparationResult.Ready).preview
                assertTrue("Prepared export contains no diagnostic values", preview.disclosure.includedValueCount > 0)
                assertNotNull("Prepared export cannot open its required review", handle.packages.approvalIntent(app, preview))
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val restored = handle.updatePolicy(previous)
            if (restored != PolicyUpdateResult.SUCCESS) {
                val error = AssertionError("Could not restore diagnostics policy: $restored, ${handle.health.value}")
                if (failure != null) failure.addSuppressed(error) else throw error
            }
        }
    }

    @Test
    fun diagnosticsUsesAppAppearanceAndReadableSystemBars() {
        val app = ApplicationProvider.getApplicationContext<StreamFerryApplication>()
        val preferences = app.container.appearancePreferences
        val previous = preferences.themeMode
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                preferences.themeMode = mode
                ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        assertEquals("Status bar appearance for $mode", mode == ThemeMode.LIGHT, controller.isAppearanceLightStatusBars)
                        assertEquals("Navigation bar appearance for $mode", mode == ThemeMode.LIGHT, controller.isAppearanceLightNavigationBars)
                    }
                    // Keep the actual rendered screen for visual inspection, not just a theme flag.
                    SystemClock.sleep(500)
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(app.externalCacheDir, "diagnostics-${mode.name.lowercase()}.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
        } finally {
            preferences.themeMode = previous
        }
    }

    private fun await(message: () -> String = { "Diagnostics did not reach the expected state" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message(), condition())
    }
}
