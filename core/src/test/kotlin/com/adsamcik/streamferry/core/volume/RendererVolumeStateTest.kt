package com.adsamcik.streamferry.core.volume

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererVolumeStateTest {
    @Test fun receiverReportedVolumeBecomesTheRelativeAdjustmentBaseline() {
        val synced = RendererVolumeState().acceptReported(.35f)

        assertTrue(synced.isSynchronized)
        assertEquals(.35f, synced.level)
        assertEquals(.40f, synced.adjustedLevel(direction = 1, step = .05f))
    }

    @Test fun unsynchronizedOrInvalidValuesNeverProduceARelativeAdjustment() {
        val stale = RendererVolumeState(level = .8f).acceptReported(Float.NaN)

        assertFalse(stale.isSynchronized)
        assertEquals(.8f, stale.level)
        assertNull(stale.adjustedLevel(direction = -1, step = .05f))
    }

    @Test fun explicitVolumeSelectionCreatesANewAuthoritativeBaseline() {
        val selected = RendererVolumeState().acceptExplicit(.62f)

        assertTrue(selected.isSynchronized)
        assertEquals(.62f, selected.level)
    }

    @Test fun staleImmediateReportDoesNotReplaceARequestedVolume() {
        val requested = RendererVolumeState(level = .35f, isSynchronized = true).acceptExplicit(.70f)

        assertNull(requested.acceptReportedIfMatching(expected = .70f, reported = .35f))
        assertEquals(.70f, requested.level)
    }

    @Test fun matchingImmediateReportConfirmsTheReceiverLevel() {
        val requested = RendererVolumeState().acceptExplicit(.70f)
        val confirmed = requested.acceptReportedIfMatching(expected = .70f, reported = .695f)

        assertEquals(.695f, confirmed?.level)
        assertTrue(confirmed?.isSynchronized == true)
    }
}
