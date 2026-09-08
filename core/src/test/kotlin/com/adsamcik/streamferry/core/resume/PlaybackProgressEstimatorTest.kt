package com.adsamcik.streamferry.core.resume

import kotlin.test.*

class PlaybackProgressEstimatorTest {
    @Test fun `delivery never starts playback or confirms watching`() {
        val e = PlaybackProgressEstimator()
        e.delivered(0, 600_000, 0)
        assertNull(e.estimate(10_000, 1000))
    }

    @Test fun `sleep uses elapsed time and contiguous delivery bounds the estimate`() {
        val e = PlaybackProgressEstimator()
        e.confirm(600, true, 0)
        e.delivered(600_000, 720_000, 55_000)
        val estimate = assertNotNull(e.estimate(60_000, 1000))
        assertEquals(660, estimate.positionSeconds)
        assertEquals(600, estimate.anchorPositionSeconds)
        assertEquals(ProgressConfidence.DELIVERY, estimate.confidence)
        assertEquals(654, estimate.recoveryPositionSeconds())
        e.delivered(720_000, 725_000, 125_000)
        assertEquals(725, e.estimate(130_000, 1000)?.positionSeconds)
    }

    @Test fun `gaps and far ahead prefetch do not advance the delivery bound`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0)
        e.delivered(100_000, 110_000, 1_000)
        e.delivered(120_000, 300_000, 20_000)
        assertEquals(110, e.estimate(25_000, 1000)?.positionSeconds)
    }

    @Test fun `unknown buffer permits only a short clock estimate`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0, 2.0)
        assertEquals(120, e.estimate(10_000, 1000)?.positionSeconds)
        assertEquals(ProgressConfidence.CLOCK, e.estimate(10_000, 1000)?.confidence)
        assertNull(e.estimate(31_000, 1000))
    }

    @Test fun `duplicates cannot extend confidence indefinitely`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0)
        e.delivered(100_000, 300_000, 0)
        e.delivered(100_000, 300_000, 40_000)
        assertNull(e.estimate(40_000, 1000))
    }

    @Test fun `fresh downloads cannot replace a confirmation forever`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0)
        e.delivered(100_000, 900_000, 301_000)
        assertNull(e.estimate(301_000, 1000))
    }

    @Test fun `pause buffering and seeks hold until a real report`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0)
        e.hold()
        e.delivered(100_000, 300_000, 10_000)
        assertNull(e.estimate(20_000, 1000))
        e.confirm(110, false, 20_000)
        assertNull(e.estimate(25_000, 1000))
        e.confirm(110, true, 30_000)
        assertEquals(120, e.estimate(40_000, 1000)?.positionSeconds)
    }

    @Test fun `fresh lower report corrects an overestimate and remote seek discards the old buffer`() {
        val e = PlaybackProgressEstimator()
        e.confirm(100, true, 0)
        e.delivered(100_000, 500_000, 60_000)
        assertEquals(160, e.estimate(60_000, 1000)?.positionSeconds)
        e.confirm(120, true, 61_000)
        assertEquals(125, e.estimate(66_000, 1000)?.positionSeconds)
        assertNull(e.estimate(100_000, 1000))
    }

    @Test fun `reset clears higher old delivery and clock reversal is rejected`() {
        val e = PlaybackProgressEstimator()
        e.confirm(500, true, 100)
        e.delivered(500_000, 900_000, 100)
        e.reset()
        assertNull(e.estimate(1000, 1000))
        e.confirm(100, true, 1000)
        e.delivered(100_000, 200_000, 40_000)
        assertEquals(ProgressConfidence.DELIVERY, e.estimate(45_000, 1000)?.confidence)
        assertNull(e.estimate(500, 1000))
    }
}
