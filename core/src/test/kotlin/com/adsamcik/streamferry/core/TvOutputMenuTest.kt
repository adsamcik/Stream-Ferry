package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.stream.TvOutputMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvOutputMenuTest {

    @Test fun singleSafeCodecStillOffersAutoAndForcedFormat() {
        val options = TvOutputMenu.formatOptions(listOf("h264"), preferredVideoCodec = null)

        assertEquals(2, options.size)
        assertNull(options.first().codec)
        assertEquals("Auto", options.first().label)
        assertTrue(options.first().isSelected)
        assertEquals("h264", options.last().codec)
        assertEquals("H.264", options.last().label)
    }

    @Test fun codecAliasesAreCanonicalAndDeduplicated() {
        val options = TvOutputMenu.formatOptions(
            listOf("h265", "hevc", "avc1", "h264"),
            preferredVideoCodec = "hvc1",
        )

        assertEquals(listOf(null, "hevc", "h264"), options.map { it.codec })
        assertEquals("hevc", options.single { it.isSelected }.codec)
        assertFalse(options.first().isSelected)
    }

    @Test fun resolutionAutoLabelUsesSavedCapWhileManualOverrideIsSelected() {
        val options = TvOutputMenu.resolutionOptions(
            automaticMaxHeightPx = 2160,
            manualMaxHeightPx = 720,
        )

        assertEquals("Auto (4K)", options.first().label)
        assertFalse(options.first().isSelected)
        assertEquals(720, options.single { it.isSelected }.heightPx)
    }

    @Test fun invalidSelectionsFallBackToAuto() {
        assertTrue(TvOutputMenu.formatOptions(listOf("h264"), "made-up").first().isSelected)
        assertTrue(TvOutputMenu.resolutionOptions(1080, 999).first().isSelected)
    }
}
