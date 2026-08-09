package com.adsamcik.streamferry.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTimecodeTest {
    @Test fun `parses exact seconds minute and hour forms`() {
        assertEquals(90L, PlaybackTimecode.parse("90", 10_000L))
        assertEquals(754L, PlaybackTimecode.parse("12:34", 10_000L))
        assertEquals(3_725L, PlaybackTimecode.parse("1:02:05", 10_000L))
    }

    @Test fun `rejects malformed or out of range positions`() {
        assertNull(PlaybackTimecode.parse("1:99", 10_000L))
        assertNull(PlaybackTimecode.parse("1:02:05", 3_000L))
        assertNull(PlaybackTimecode.parse("1::05", 10_000L))
        assertNull(PlaybackTimecode.parse("-1", 10_000L))
        assertNull(PlaybackTimecode.parse("999999999999999999999", Long.MAX_VALUE))
    }
}
