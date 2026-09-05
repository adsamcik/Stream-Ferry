package com.adsamcik.streamferry.playback

import android.app.ActivityManager
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.app.MainActivity
import com.adsamcik.streamferry.playback.proxy.ProxyPlaybackService
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a phone with no active playback. Exercises Android's real queued service lifecycle. */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceLifecycleTest {
    @Suppress("DEPRECATION") // Android still exposes the calling application's own services.
    @Test fun rapidReplacementRetainsForegroundServiceWithScreenOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        fun foreground() = manager.getRunningServices(100).any {
            it.service.className == ProxyPlaybackService::class.java.name && it.foreground
        }
        assumeFalse("Do not interrupt active playback", foreground())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val power = context.getSystemService(PowerManager::class.java)
        val wasInteractive = power.isInteractive
        fun key(code: Int) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent $code").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        }
        try {
            repeat(20) {
                // Hold the app main thread until both requests are queued. The old implementation
                // delivered STOP.stopSelf() before START and destroyed the newly foregrounded service.
                instrumentation.runOnMainSync {
                    ProxyPlaybackService.stop(context)
                    ProxyPlaybackService.start(context, -1)
                }
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (!foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertTrue("Android foreground service missing after replacement $it", foreground())
            }
            key(223) // KEYCODE_SLEEP
            SystemClock.sleep(15_000)
            assertFalse("Screen must be off for this check", power.isInteractive)
            assertTrue("Foreground protection lost with screen off", foreground())
            assertTrue(ProxyPlaybackService.isForegroundActive())
        } finally {
            ProxyPlaybackService.stop(context)
            instrumentation.runOnMainSync { activity.finish() }
            if (wasInteractive) key(224) // KEYCODE_WAKEUP
        }
    }
}
