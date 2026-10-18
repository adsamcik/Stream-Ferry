package com.adsamcik.streamferry.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHealthTrackerTest {
    @Test fun `fresh frozen reports detect a stall after forty five seconds`() {
        val tracker = PlaybackHealthTracker()
        assertFalse(tracker.observe(1, 120, 0))
        for (now in 5_000L..40_000L step 5_000) {
            assertFalse(tracker.observe(1, 120, now))
            assertFalse(tracker.isStalled(1, now))
        }
        assertFalse(tracker.observe(1, 120, 45_000))
        assertTrue(tracker.isStalled(1, 45_000))
        assertFalse(tracker.isStalled(2, 45_000))
        assertFalse(tracker.observe(1, 121, 50_000))
        assertFalse(tracker.isStalled(1, 50_000))
    }

    @Test fun `missing reports cannot establish a stall`() {
        val tracker = PlaybackHealthTracker()
        tracker.observe(1, 120, 0)
        assertFalse(tracker.isStalled(1, 45_000))
        tracker.observe(1, 120, 45_000)
        assertFalse(tracker.isStalled(1, 45_000))
        // Fresh reports after the gap start a new observation window.
        for (now in 50_000L..90_000L step 5_000) tracker.observe(1, 120, now)
        assertTrue(tracker.isStalled(1, 90_000))
        assertFalse(tracker.isStalled(1, 105_001))
    }

    @Test fun `confirmed continuous progress needs a full healthy minute`() {
        val tracker = PlaybackHealthTracker()
        for (second in 0L..50L step 10) {
            assertFalse(tracker.observe(1, 120 + second, second * 1_000))
        }
        assertTrue(tracker.observe(1, 180, 60_000))
        assertFalse(tracker.isStalled(1, 60_000))
    }

    @Test fun `frozen reports never rearm network capacity and break an earlier healthy window`() {
        val tracker = PlaybackHealthTracker()
        for (second in 0L..50L step 10) tracker.observe(1, 120 + second, second * 1_000)
        for (now in 55_000L..70_000L step 5_000) assertFalse(tracker.observe(1, 170, now))
        for (second in 5L..55L step 5) {
            assertFalse(tracker.observe(1, 170 + second, 70_000 + second * 1_000))
        }
        assertTrue(tracker.observe(1, 235, 135_000))
    }

    @Test fun `reset and replacement generations cannot inherit previous healthy progress`() {
        for (replacement in listOf(false, true)) {
            val tracker = PlaybackHealthTracker()
            for (second in 0L..50L step 10) tracker.observe(1, 120 + second, second * 1_000)
            if (!replacement) tracker.reset()
            val generation = if (replacement) 2L else 1L
            assertFalse(tracker.observe(generation, 180, 60_000))
            for (second in 10L..50L step 10) {
                assertFalse(tracker.observe(generation, 180 + second, 60_000 + second * 1_000))
            }
            assertTrue(tracker.observe(generation, 240, 120_000))
        }
    }

    @Test fun `report gaps backward seeks large jumps and clock resets restart healthy qualification`() {
        data class Discontinuity(val position: Long, val now: Long)
        for (change in listOf(
            Discontinuity(186, 66_000), // sixteen-second report gap
            Discontinuity(100, 60_000), // backward seek
            Discontinuity(600, 60_000), // forward seek
            Discontinuity(170, 49_000), // backwards monotonic-clock input
        )) {
            val tracker = PlaybackHealthTracker()
            for (second in 0L..50L step 10) tracker.observe(1, 120 + second, second * 1_000)
            assertFalse(tracker.observe(1, change.position, change.now))
            for (second in 10L..50L step 10) {
                assertFalse(tracker.observe(1, change.position + second, change.now + second * 1_000))
            }
            assertTrue(tracker.observe(1, change.position + 60, change.now + 60_000))
        }
    }
}
