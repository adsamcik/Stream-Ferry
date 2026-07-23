package com.videobridge.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [SeekSettle.shouldHold] — the pure decision that keeps the phone's scrubber pinned to a seek
 * target until the renderer confirms it, so a status poll that was in flight when the seek was issued
 * (esp. DLNA) can't snap the position backward.
 */
class SeekSettleTest {

    // Canonical case: a seek to 800s is being settled, the window closes at t=4000ms, tolerance 5s.
    private fun hold(
        targetSeconds: Long? = 800L,
        incomingSeconds: Long = 800L,
        nowMs: Long = 0L,
        untilMs: Long = 4000L,
        toleranceSeconds: Long = 5L,
    ) = SeekSettle.shouldHold(targetSeconds, incomingSeconds, nowMs, untilMs, toleranceSeconds)

    @Test fun noSeekPendingNeverHolds() =
        assertFalse(hold(targetSeconds = null, incomingSeconds = 5L))

    @Test fun staleStatusWithinWindowIsHeld() =
        // Renderer still reports the OLD position (12s) while we're seeking to 800s, inside the window.
        assertTrue(hold(incomingSeconds = 12L, nowMs = 500L))

    @Test fun confirmingStatusIsApplied() =
        // Renderer reports right at the target -> seek confirmed, apply it (stop holding).
        assertFalse(hold(incomingSeconds = 800L, nowMs = 500L))

    @Test fun statusWithinToleranceIsApplied() =
        // A few seconds of buffering/playback past the target still counts as confirmed.
        assertFalse(hold(incomingSeconds = 803L, nowMs = 500L))

    @Test fun statusJustOutsideToleranceIsHeld() =
        assertTrue(hold(incomingSeconds = 806L, nowMs = 500L))

    @Test fun staleStatusAfterWindowIsApplied() =
        // Backstop: a seek the renderer clamped/ignored self-corrects once the window elapses.
        assertFalse(hold(incomingSeconds = 12L, nowMs = 4001L))

    @Test fun backwardSeekHoldsStaleForwardStatus() =
        // Seeking backward to 100s while the renderer still reports the old (later) 800s position.
        assertTrue(hold(targetSeconds = 100L, incomingSeconds = 800L, nowMs = 500L))

    @Test fun backwardSeekConfirmsAtTarget() =
        assertFalse(hold(targetSeconds = 100L, incomingSeconds = 100L, nowMs = 500L))
}
