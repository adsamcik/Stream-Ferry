package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.adaptive.AdaptiveBitrateController
import com.adsamcik.streamferry.core.adaptive.AdaptiveBitrateController.Decision
import com.adsamcik.streamferry.core.adaptive.AdaptiveBitrateController.Direction
import com.adsamcik.streamferry.core.adaptive.BitrateLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdaptiveBitrateControllerTest {

    private val ladder = BitrateLadder.DEFAULT // [0.72,1.5,3,4,6,8,12,20,40] Mbps

    /** Mutable clock so each test drives time deterministically. */
    private class Clock(var now: Long = 1_000_000L)

    private fun controller(startBitrate: Long?, clock: Clock, config: AdaptiveBitrateController.Config = AdaptiveBitrateController.Config()) =
        AdaptiveBitrateController(ladder, startBitrate, config) { clock.now }

    /** Feed [seconds] one-second throughput samples at [bps], advancing the clock each second. */
    private fun feed(c: AdaptiveBitrateController, clock: Clock, bps: Long, seconds: Int) {
        repeat(seconds) {
            c.recordThroughput(bps / 8, clock.now)
            clock.now += 1_000
        }
    }

    @Test fun holdsDuringWarmupBefore30s() {
        val clock = Clock()
        val c = controller(8_000_000, clock)
        feed(c, clock, 2_000_000, seconds = 20) // only 20s of data
        val d = c.evaluate(clock.now)
        assertIs<Decision.Hold>(d)
        assertTrue(d.reason.contains("warming up"))
    }

    @Test fun averageThroughputIsComputedOverWindow() {
        val clock = Clock()
        val c = controller(8_000_000, clock)
        feed(c, clock, 5_000_000, seconds = 31)
        // Average should be ~5 Mbps (within rounding of the 30s window).
        val avg = c.averageThroughputBps(clock.now)
        assertTrue(avg in 4_800_000..5_200_000, "avg was $avg")
    }

    @Test fun stepsDownWhenThroughputCannotSustainCurrent() {
        val clock = Clock()
        val c = controller(startBitrate = 20_000_000, clock = clock) // index 7
        feed(c, clock, bps = 5_000_000, seconds = 31)
        val d = c.evaluate(clock.now)
        assertIs<Decision.ChangeBitrate>(d)
        assertEquals(Direction.DOWN, d.direction)
        // budget = 5Mbps * 0.8 = 4Mbps -> highest rung <= 4Mbps is the 4 Mbps rung.
        assertEquals(4_000_000L, d.newBitrateBps)
    }

    @Test fun stepsDownOnRebufferingEvenIfAverageLooksOk() {
        val clock = Clock()
        val c = controller(startBitrate = 6_000_000, clock = clock) // index 4
        feed(c, clock, bps = 6_500_000, seconds = 31) // avg slightly above current
        c.recordRebuffer(clock.now - 2_000)
        val d = c.evaluate(clock.now)
        assertIs<Decision.ChangeBitrate>(d)
        assertEquals(Direction.DOWN, d.direction)
        assertTrue(d.newBitrateBps < 6_000_000L)
        assertTrue(d.reason.contains("rebuffering"))
    }

    @Test fun stepsUpOneRungWhenComfortablyAboveNext() {
        val clock = Clock()
        val c = controller(startBitrate = 3_000_000, clock = clock) // index 2; next rung 4 Mbps
        feed(c, clock, bps = 6_000_000, seconds = 31) // 6 >= 4 * 1.3 (=5.2)
        val d = c.evaluate(clock.now)
        assertIs<Decision.ChangeBitrate>(d)
        assertEquals(Direction.UP, d.direction)
        assertEquals(4_000_000L, d.newBitrateBps) // exactly one rung up
    }

    @Test fun doesNotStepUpWithoutHeadroom() {
        val clock = Clock()
        val c = controller(startBitrate = 3_000_000, clock = clock) // next rung 4 Mbps, needs >5.2
        feed(c, clock, bps = 4_500_000, seconds = 31) // above current but < 5.2 headroom
        val d = c.evaluate(clock.now)
        assertIs<Decision.Hold>(d)
    }

    @Test fun doesNotStepBelowLowestRung() {
        val clock = Clock()
        val c = controller(startBitrate = ladder.first(), clock = clock) // index 0
        feed(c, clock, bps = 100_000, seconds = 31) // far below lowest rung
        val d = c.evaluate(clock.now)
        assertIs<Decision.Hold>(d) // nowhere lower to go
    }

    @Test fun windowResetsAfterApplyingAChange() {
        val clock = Clock()
        val c = controller(startBitrate = 20_000_000, clock = clock)
        feed(c, clock, bps = 5_000_000, seconds = 31)
        val d = c.evaluate(clock.now)
        assertIs<Decision.ChangeBitrate>(d)
        c.noteApplied(d.newIndex, clock.now)
        assertEquals(d.newBitrateBps, c.currentBitrateBps)
        assertEquals(0L, c.averageThroughputBps(clock.now)) // measurement window cleared
    }

    @Test fun honoursCooldownBetweenSwitches() {
        val clock = Clock()
        // Long switch interval but normal observation window isolates the cooldown gate.
        val config = AdaptiveBitrateController.Config(minSwitchIntervalMillis = 120_000)
        val c = controller(startBitrate = 20_000_000, clock = clock, config = config)
        feed(c, clock, bps = 5_000_000, seconds = 31)
        val first = c.evaluate(clock.now)
        assertIs<Decision.ChangeBitrate>(first)
        c.noteApplied(first.newIndex, clock.now)
        // Accumulate another full window of poor throughput, but still within the 120s cooldown.
        feed(c, clock, bps = 1_000_000, seconds = 31)
        val second = c.evaluate(clock.now)
        assertIs<Decision.Hold>(second)
        assertTrue(second.reason.contains("cooldown"))
    }

    @Test fun ladderForSourceCapsAtSourceBitrate() {
        val l = BitrateLadder.forSource(5_000_000)
        assertTrue(l.last() == 5_000_000L)
        assertTrue(l.all { it <= 5_000_000L })
        assertTrue(l.zipWithNext().all { (a, b) -> a < b }) // strictly ascending, de-duped
    }

    @Test fun rebufferCountInWindowCountsRecentAndPrunesOld() {
        val clock = Clock()
        val c = controller(startBitrate = ladder.first(), clock = clock) // at the floor
        c.recordRebuffer(clock.now)
        c.recordRebuffer(clock.now)
        c.recordRebuffer(clock.now)
        assertEquals(3, c.rebufferCountInWindow(clock.now)) // a stall storm at the lowest rung
        clock.now += 31_000 // advance past the 30s window
        assertEquals(0, c.rebufferCountInWindow(clock.now)) // old rebuffers pruned
    }
}
