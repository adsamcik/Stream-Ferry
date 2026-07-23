package com.videobridge.core.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers [QualityMenu.options] — the pure manual-quality menu builder (Auto + ladder rungs, best first). */
class QualityMenuTest {

    private val ladder = listOf(1_500_000L, 3_000_000L, 6_000_000L, 20_000_000L)

    @Test fun autoIsFirstAndSelectedWhenNoPin() {
        val opts = QualityMenu.options(ladder, pinnedBitrateBps = null)
        assertNull(opts.first().bitrateBps)
        assertEquals("Auto", opts.first().label)
        assertTrue(opts.first().isSelected)
        assertTrue(opts.drop(1).none { it.isSelected }) // no rung selected in Auto mode
    }

    @Test fun rungsAreListedBestQualityFirst() {
        val opts = QualityMenu.options(ladder, pinnedBitrateBps = null).drop(1)
        assertEquals(listOf(20_000_000L, 6_000_000L, 3_000_000L, 1_500_000L), opts.map { it.bitrateBps })
    }

    @Test fun labelsAreFormattedInMbps() {
        val opts = QualityMenu.options(ladder, pinnedBitrateBps = null).associate { it.bitrateBps to it.label }
        assertEquals("1.5 Mbps", opts[1_500_000L])
        assertEquals("6.0 Mbps", opts[6_000_000L])
        assertEquals("20.0 Mbps", opts[20_000_000L])
    }

    @Test fun pinnedRungIsSelectedAndAutoIsNot() {
        val opts = QualityMenu.options(ladder, pinnedBitrateBps = 6_000_000L)
        assertTrue(!opts.first().isSelected) // Auto not selected when pinned
        val selected = opts.filter { it.isSelected }
        assertEquals(listOf(6_000_000L), selected.map { it.bitrateBps })
    }

    @Test fun offLadderPinSnapsToHighestRungAtOrBelow() {
        // 5 Mbps isn't a rung; it snaps to 3 Mbps (highest <= 5), matching what the engine would stream.
        val opts = QualityMenu.options(ladder, pinnedBitrateBps = 5_000_000L)
        assertEquals(listOf(3_000_000L), opts.filter { it.isSelected }.map { it.bitrateBps })
    }

    @Test fun emptyLadderStillOffersAuto() {
        val opts = QualityMenu.options(emptyList(), pinnedBitrateBps = null)
        assertEquals(1, opts.size)
        assertNull(opts.single().bitrateBps)
    }
}
