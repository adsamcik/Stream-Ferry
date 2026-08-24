package com.adsamcik.streamferry.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPositionPolicyTest {
    @Test fun `positions clamp to a known timeline`() {
        assertEquals(0L, PlaybackPositionPolicy.clamp(-30L, 1_000L))
        assertEquals(450L, PlaybackPositionPolicy.clamp(450L, 1_000L))
        assertEquals(1_000L, PlaybackPositionPolicy.clamp(1_200L, 1_000L))
    }

    @Test fun `unknown duration only rejects negative positions`() {
        assertEquals(9_000L, PlaybackPositionPolicy.clamp(9_000L, null))
        assertEquals(0L, PlaybackPositionPolicy.clamp(-1L, null))
    }

    @Test fun `direct server local downloaded and vod hls modes seek on the renderer`() {
        assertFalse(PlaybackPositionPolicy.requiresServerReload(isTranscoding = false, isHls = false))
        assertFalse(PlaybackPositionPolicy.requiresServerReload(isTranscoding = false, isHls = true))
        assertFalse(PlaybackPositionPolicy.requiresServerReload(isTranscoding = true, isHls = true))
    }

    @Test fun `only progressive server transcode seeks by reloading upstream`() {
        assertTrue(PlaybackPositionPolicy.requiresServerReload(isTranscoding = true, isHls = false))
        assertEquals(0L, PlaybackPositionPolicy.rendererLoadPosition(735L, requiresServerReload = true))
        assertEquals(735L, PlaybackPositionPolicy.rendererLoadPosition(735L, requiresServerReload = false))
    }

    @Test fun `natural completion finalizes a known timeline despite a stale renderer clock`() {
        assertEquals(1_000L, PlaybackPositionPolicy.completedPosition(500L, 1_000L))
    }

    @Test fun `natural completion retains the last position when the timeline is unknown`() {
        assertEquals(500L, PlaybackPositionPolicy.completedPosition(500L, null))
        assertEquals(0L, PlaybackPositionPolicy.completedPosition(-1L, null))
    }
}
