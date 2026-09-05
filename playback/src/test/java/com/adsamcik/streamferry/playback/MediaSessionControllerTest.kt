package com.adsamcik.streamferry.playback

import io.mockk.mockk
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSessionControllerTest {
    @Test fun `position updates reuse notification while pause and restart refresh controls`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = MediaSessionController(context, mockk(relaxed = true), null)
        val status = PlaybackStatus(
            "TV", "CAST", isPlaying = true, isBuffering = false, positionSeconds = 1,
            durationSeconds = 3600, streamMode = "Direct", currentBitrateBps = 1000,
            measuredThroughputBps = 2000, adaptiveNote = "", title = "Movie", phase = PlaybackPhase.PLAYING,
        )
        try {
            controller.update(status)
            val first = controller.currentNotification()
            controller.update(status.copy(positionSeconds = 2, measuredThroughputBps = 3000))
            assertSame(first, controller.currentNotification())
            controller.update(status.copy(isPlaying = false))
            assertNotSame(first, controller.currentNotification())
            controller.update(null)
            val paused = controller.currentNotification()
            controller.update(status.copy(isPlaying = false))
            assertNotSame(paused, controller.currentNotification(), "Restart must republish even when content matches")
        } finally { controller.release() }
    }
}
