package com.adsamcik.streamferry.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackControlPolicyTest {
    @Test fun `teardown never waits for a known disconnected renderer`() {
        assertTrue(PlaybackTeardownPolicy.shouldSendRendererStop(connectionLost = false))
        assertFalse(PlaybackTeardownPolicy.shouldSendRendererStop(connectionLost = true))
    }

    @Test fun `playing paused and buffering sessions remain controllable`() {
        listOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING).forEach { phase ->
            val controls = PlaybackControlPolicy.evaluate(phase, durationSeconds = 3_600L)
            assertTrue(controls.canPlayPause)
            assertTrue(controls.canSkip)
            assertTrue(controls.canSeekTimeline)
        }
    }

    @Test fun `relative skip works when duration is unknown but absolute timeline does not`() {
        val controls = PlaybackControlPolicy.evaluate(PlaybackPhase.PLAYING, durationSeconds = null)
        assertTrue(controls.canSkip)
        assertFalse(controls.canSeekTimeline)
    }

    @Test fun `transitions and terminal phases reject stale interaction`() {
        listOf(
            PlaybackPhase.PREPARING,
            PlaybackPhase.RECONNECTING,
            PlaybackPhase.CHANGING_STREAM,
            PlaybackPhase.COMPLETED,
            PlaybackPhase.FAILED,
        ).forEach { phase ->
            val controls = PlaybackControlPolicy.evaluate(phase, durationSeconds = 3_600L)
            assertFalse(controls.canPlayPause)
            assertFalse(controls.canSkip)
            assertFalse(controls.canSeekTimeline)
        }
    }
}
