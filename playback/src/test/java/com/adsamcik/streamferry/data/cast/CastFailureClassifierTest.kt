package com.adsamcik.streamferry.data.cast

import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import org.junit.Test
import kotlin.test.assertEquals

class CastFailureClassifierTest {
    @Test fun `media download and license failures are network failures`() {
        for (code in listOf(103, 201, 300, 301, 311, 312, 313, 314, 321, 331)) {
            assertEquals(PlaybackFailureKind.NETWORK, CastFailureClassifier.mediaError(code), "code=$code")
        }
    }

    @Test fun `only explicit decode and source unsupported errors establish format evidence`() {
        for (code in listOf(MediaError.DetailedErrorCode.MEDIA_DECODE, MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED)) {
            assertEquals(PlaybackFailureKind.FORMAT, CastFailureClassifier.mediaError(code))
        }
        for (code in listOf(null, 100, 101, 110, 202, 315, 316, 322, 400, 411, 500, 904, 905, 999, 12345)) {
            assertEquals(PlaybackFailureKind.UNKNOWN, CastFailureClassifier.mediaError(code), "code=$code")
        }
    }

    @Test fun `sender network and suspension statuses take precedence over media evidence`() {
        for (status in listOf(CastStatusCodes.NETWORK_ERROR, CastStatusCodes.TIMEOUT, CastStatusCodes.DEVICE_CONNECTION_SUSPENDED)) {
            assertEquals(PlaybackFailureKind.NETWORK, CastFailureClassifier.command(status, MediaError.DetailedErrorCode.MEDIA_DECODE))
        }
        assertEquals(PlaybackFailureKind.UNKNOWN, CastFailureClassifier.command(CastStatusCodes.FAILED, null))
        assertEquals(PlaybackFailureKind.FORMAT, CastFailureClassifier.command(CastStatusCodes.MEDIA_ERROR, MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED))
    }
}
