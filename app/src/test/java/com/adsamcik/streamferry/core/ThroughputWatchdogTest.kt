package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.resilience.ThroughputWatchdog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThroughputWatchdogTest {

    @Test fun stallsOnSustainedLowThroughput() {
        val w = ThroughputWatchdog(minBytesPerWindow = 1000, windowMillis = 1000, graceMillis = 0, maxLowWindows = 3)
        assertFalse(w.record(10, 0))
        assertFalse(w.record(10, 1000)) // low window #1
        assertFalse(w.record(10, 2000)) // low window #2
        assertTrue(w.record(10, 3000)) // low window #3 -> give up
    }

    @Test fun healthyStreamNeverStalls() {
        val w = ThroughputWatchdog(minBytesPerWindow = 1000, windowMillis = 1000, graceMillis = 0, maxLowWindows = 2)
        var t = 0L
        repeat(20) {
            assertFalse(w.record(5000, t)) // 5000 bytes per 500ms is well above the floor
            t += 500
        }
    }

    @Test fun pauseDoesNotStall() {
        val w = ThroughputWatchdog(minBytesPerWindow = 1000, windowMillis = 1000, graceMillis = 0, maxLowWindows = 2)
        assertFalse(w.record(10, 0))
        assertFalse(w.record(10, 1000)) // low window #1
        assertFalse(w.record(10, 5000)) // long gap (paused TV) -> window + strikes reset
        assertFalse(w.record(10, 6000)) // low window #1 again, not #2
        assertFalse(w.record(2000, 7000)) // a healthy window clears the strike
    }

    @Test fun graceSuppressesEarlyStall() {
        val w = ThroughputWatchdog(minBytesPerWindow = 1000, windowMillis = 1000, graceMillis = 5000, maxLowWindows = 1)
        assertFalse(w.record(1, 0))
        assertFalse(w.record(1, 1000)) // window elapsed but still within grace
        assertFalse(w.record(1, 2000))
    }
}
