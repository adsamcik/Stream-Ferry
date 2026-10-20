package com.adsamcik.streamferry.app

import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackTargetException
import com.adsamcik.streamferry.playback.PlaybackAttemptDescriptor
import com.adsamcik.streamferry.playback.PlaybackFailureCause
import com.adsamcik.streamferry.playback.PlaybackFailureStage
import com.adsamcik.streamferry.playback.PlaybackPreparationException
import com.adsamcik.streamferry.source.api.SourceHttpException
import java.io.FileNotFoundException
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackFailureMessageTest {
    @Test fun `source failure shows its HTTP outcome without exposing the server response`() {
        val failure = PlaybackPreparationException("Source setup failed",
            SourceHttpException(503, "https://private.example/video?api_key=secret"))
        val message = playbackFailureMessage(failure, null)
        assertContains(message, "HTTP 503")
        assertContains(message, "Check the server")
        assertFalse(message.contains("private.example"))
        assertFalse(message.contains("secret"))
    }

    @Test fun `connection failure and format rejection give different guidance`() {
        val network = playbackFailureMessage(PlaybackTargetException(PlaybackFailureKind.NETWORK, "raw detail"),
            PlaybackAttemptDescriptor(1, failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION))
        val format = playbackFailureMessage(PlaybackTargetException(PlaybackFailureKind.FORMAT, "raw detail"), null)
        assertContains(network, "reach the TV")
        assertContains(format, "rejected this video format")
        assertFalse(network.contains("format"))
    }

    @Test fun `latest recovery evidence wins over an earlier protocol failure`() {
        val message = playbackFailureMessage(PlaybackTargetException(PlaybackFailureKind.FORMAT, "first protocol"),
            PlaybackAttemptDescriptor(2, failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE))
        assertContains(message, "media source")
        assertFalse(message.contains("rejected this video format"))
    }

    @Test fun `missing file and denied access are explained without private paths`() {
        assertContains(playbackFailureMessage(FileNotFoundException("/private/user/video.mp4"), null), "Select the file again")
        val message = playbackFailureMessage(SecurityException("content://private/video"), null)
        assertContains(message, "Allow access")
        assertFalse(message.contains("content://"))
    }

    @Test fun `phone service failure is not attributed to the TV`() {
        val message = playbackFailureMessage(PlaybackPreparationException("The phone playback service was rejected.",
            SecurityException("android internals")), PlaybackAttemptDescriptor(1,
            failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
            failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE))
        assertContains(message, "playback service")
        assertContains(message, "Keep Stream Ferry open")
        assertFalse(message.contains("media source"))
    }

    @Test fun `app authored preflight advice survives wrapping and an unknown cause stays unknown`() {
        val advice = "The downloaded copy is missing or incomplete. Download this video again."
        assertEquals(advice, playbackFailureMessage(IllegalStateException("wrapper", PlaybackRequestException(advice)), null))
        val unknown = playbackFailureMessage(IllegalStateException("Private info token=secret"), null)
        assertContains(unknown, "without a specific reason")
        assertFalse(unknown.contains("Private info"))
        assertFalse(unknown.contains("secret"))
    }
}
