package com.videobridge.playback

import com.videobridge.domain.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the pure startup-watchdog heuristics (ideas 1+7): silent-failure classification + early-bail detection. */
class StartupWatchdogTest {

    @Test fun graceTimeoutWithRealProgressIsFormat() {
        assertEquals(PlaybackFailureKind.FORMAT, StartupWatchdog.graceTimeoutKind(StartupWatchdog.MIN_PROGRESS_BYTES))
        assertEquals(PlaybackFailureKind.FORMAT, StartupWatchdog.graceTimeoutKind(50L * 1024 * 1024))
    }

    @Test fun graceTimeoutWithAlmostNoBytesIsUnknown() {
        assertEquals(PlaybackFailureKind.UNKNOWN, StartupWatchdog.graceTimeoutKind(0))
        assertEquals(PlaybackFailureKind.UNKNOWN, StartupWatchdog.graceTimeoutKind(StartupWatchdog.MIN_PROGRESS_BYTES - 1))
    }

    @Test fun earlyRejectionWhenReadSomeThenBailedQuickly() {
        // 1 MiB read in 2s, then closed, before playback started — a "tried then gave up" rejection.
        assertTrue(StartupWatchdog.isEarlyRejection(bytesServed = 1L * 1024 * 1024, durationMs = 2_000))
    }

    @Test fun tinyProbeReadIsNotARejection() {
        // A range/HEAD probe reads almost nothing — must not be mistaken for a rejection.
        assertFalse(StartupWatchdog.isEarlyRejection(bytesServed = 1_024, durationMs = 200))
        assertFalse(StartupWatchdog.isEarlyRejection(bytesServed = StartupWatchdog.PROBE_BYTES - 1, durationMs = 200))
    }

    @Test fun largeReadIsHealthyBufferingNotRejection() {
        assertFalse(StartupWatchdog.isEarlyRejection(bytesServed = StartupWatchdog.VIABLE_BYTES, durationMs = 2_000))
        assertFalse(StartupWatchdog.isEarlyRejection(bytesServed = 32L * 1024 * 1024, durationMs = 2_000))
    }

    @Test fun slowCloseIsNotAnEarlyBailout() {
        assertFalse(StartupWatchdog.isEarlyRejection(bytesServed = 1L * 1024 * 1024, durationMs = StartupWatchdog.FAST_FAIL_MS))
    }

    @Test fun rejectionBandIsInclusiveOfProbeLowerBound() {
        assertTrue(StartupWatchdog.isEarlyRejection(bytesServed = StartupWatchdog.PROBE_BYTES, durationMs = 1_000))
    }
}
