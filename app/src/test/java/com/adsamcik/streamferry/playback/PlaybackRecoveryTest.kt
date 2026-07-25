package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [decideRecovery] — the pure policy that maps a classified renderer failure + session state to a
 * [RecoveryAction]. Applies uniformly to Cast and DLNA online sessions.
 */
class PlaybackRecoveryTest {

    // Defaults describe the canonical recoverable case: a first FORMAT failure of an online, direct-play
    // session that isn't already transcoding. Each test flips one field to assert a guard.
    private fun decide(
        failure: PlaybackFailureKind = PlaybackFailureKind.FORMAT,
        isReloading: Boolean = false,
        isOnlineSession: Boolean = true,
        preferDirectPlay: Boolean = true,
        alreadyTranscoding: Boolean = false,
        alreadyRetried: Boolean = false,
        hasLowerResolutionFallback: Boolean = false,
    ) = decideRecovery(
        failure = failure,
        isReloading = isReloading,
        isOnlineSession = isOnlineSession,
        preferDirectPlay = preferDirectPlay,
        alreadyTranscoding = alreadyTranscoding,
        alreadyRetried = alreadyRetried,
        hasLowerResolutionFallback = hasLowerResolutionFallback,
    )

    @Test fun formatFailureFallsBackToTranscode() =
        assertEquals(RecoveryAction.TRANSCODE_FALLBACK, decide(failure = PlaybackFailureKind.FORMAT))

    @Test fun unknownFailureFallsBackToTranscodeOptimistically() =
        assertEquals(RecoveryAction.TRANSCODE_FALLBACK, decide(failure = PlaybackFailureKind.UNKNOWN))

    @Test fun networkFailureRetriesSameStreamOnce() =
        assertEquals(RecoveryAction.RETRY_SAME_STREAM, decide(failure = PlaybackFailureKind.NETWORK))

    @Test fun networkFailureAfterRetrySurfaces() =
        assertEquals(RecoveryAction.SURFACE, decide(failure = PlaybackFailureKind.NETWORK, alreadyRetried = true))

    @Test fun errorWhileReloadingIsIgnored() =
        assertEquals(RecoveryAction.IGNORE_DURING_RELOAD, decide(isReloading = true))

    @Test fun reloadIgnoreTakesPrecedenceOverNetworkRetry() =
        assertEquals(
            RecoveryAction.IGNORE_DURING_RELOAD,
            decide(failure = PlaybackFailureKind.NETWORK, isReloading = true),
        )

    @Test fun serverTranscodeFailureStepsDownResolutionBeforeGivingUp() =
        assertEquals(
            RecoveryAction.LOWER_RESOLUTION_FALLBACK,
            decide(alreadyTranscoding = true, hasLowerResolutionFallback = true),
        )

    @Test fun localFileFormatFailureSurfacesBecauseThereIsNoServer() =
        assertEquals(RecoveryAction.SURFACE, decide(failure = PlaybackFailureKind.FORMAT, isOnlineSession = false))

    @Test fun errorWhileAlreadyTranscodingSurfaces() =
        assertEquals(RecoveryAction.SURFACE, decide(alreadyTranscoding = true))

    @Test fun directPlayDisabledSurfaces() =
        assertEquals(RecoveryAction.SURFACE, decide(preferDirectPlay = false))

}
