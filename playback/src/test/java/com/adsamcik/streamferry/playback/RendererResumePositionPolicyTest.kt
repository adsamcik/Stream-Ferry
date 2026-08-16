package com.adsamcik.streamferry.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class RendererResumePositionPolicyTest {
    private val pending = PendingRendererResumePosition(
        loadGeneration = 7L,
        expectedPositionSeconds = 1_245L,
    )

    @Test fun `resume confirmation waits until the replacement stream is playing`() {
        assertEquals(
            RendererResumePositionAction.Wait,
            RendererResumePositionPolicy.evaluate(pending, reportedPositionSeconds = 0L, isPlaying = false),
        )
    }

    @Test fun `resume is confirmed when the TV reports the requested position within tolerance`() {
        assertEquals(
            RendererResumePositionAction.Confirmed,
            RendererResumePositionPolicy.evaluate(pending, reportedPositionSeconds = 1_249L, isPlaying = true),
        )
    }

    @Test fun `a playing TV at another position receives the exact resume target`() {
        assertEquals(
            RendererResumePositionAction.Correct(positionSeconds = 1_245L),
            RendererResumePositionPolicy.evaluate(pending, reportedPositionSeconds = 18L, isPlaying = true),
        )
    }

    @Test fun `only one correction is active and retries are bounded`() {
        val first = RendererResumePositionPolicy.correctionStarted(pending)
        assertEquals(
            RendererResumePositionAction.Wait,
            RendererResumePositionPolicy.evaluate(first, reportedPositionSeconds = 18L, isPlaying = true),
        )

        val second = RendererResumePositionPolicy.correctionStarted(
            RendererResumePositionPolicy.correctionFinished(first),
        )
        val exhausted = RendererResumePositionPolicy.correctionFinished(second)
        assertEquals(
            RendererResumePositionAction.GiveUp,
            RendererResumePositionPolicy.evaluate(exhausted, reportedPositionSeconds = 18L, isPlaying = true),
        )
    }
}
