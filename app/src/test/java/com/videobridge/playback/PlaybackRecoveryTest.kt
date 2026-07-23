package com.videobridge.playback

import com.videobridge.domain.PlaybackFailureKind
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
        onDeviceOnlineEnabled: Boolean = false,
        onDeviceAlreadyAttempted: Boolean = false,
        receiverSupportsHls: Boolean = true,
    ) = decideRecovery(
        failure, isReloading, isOnlineSession, preferDirectPlay, alreadyTranscoding, alreadyRetried,
        onDeviceOnlineEnabled, onDeviceAlreadyAttempted, receiverSupportsHls,
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

    @Test fun localFileFormatFailureSurfacesBecauseThereIsNoServer() =
        assertEquals(RecoveryAction.SURFACE, decide(failure = PlaybackFailureKind.FORMAT, isOnlineSession = false))

    @Test fun errorWhileAlreadyTranscodingSurfaces() =
        assertEquals(RecoveryAction.SURFACE, decide(alreadyTranscoding = true))

    @Test fun directPlayDisabledSurfaces() =
        assertEquals(RecoveryAction.SURFACE, decide(preferDirectPlay = false))

    @Test fun serverTranscodeExhaustedFallsBackToOnDeviceWhenEnabled() =
        // Already server-transcoding + user opted into on-device online transcode -> try the phone.
        assertEquals(
            RecoveryAction.ONDEVICE_TRANSCODE_FALLBACK,
            decide(alreadyTranscoding = true, onDeviceOnlineEnabled = true),
        )

    @Test fun onDeviceFallbackNotUsedWhenReceiverCannotPlayHls() =
        // A DLNA renderer can't play the phone's HLS/CMAF on-device transcode, so surface instead of
        // falling into an HLS-over-DLNA dead end (UPnP 701 / silent hang).
        assertEquals(
            RecoveryAction.SURFACE,
            decide(alreadyTranscoding = true, onDeviceOnlineEnabled = true, receiverSupportsHls = false),
        )

    @Test fun onDeviceFallbackNotUsedWhenDisabled() =
        assertEquals(RecoveryAction.SURFACE, decide(alreadyTranscoding = true, onDeviceOnlineEnabled = false))

    @Test fun onDeviceFallbackNotRepeatedOnceAttempted() =
        assertEquals(
            RecoveryAction.SURFACE,
            decide(alreadyTranscoding = true, onDeviceOnlineEnabled = true, onDeviceAlreadyAttempted = true),
        )

    @Test fun onDeviceFallbackNotUsedForLocalSession() =
        // A local file has no server; server transcode isn't relevant and on-device is the primary path.
        assertEquals(
            RecoveryAction.SURFACE,
            decide(isOnlineSession = false, onDeviceOnlineEnabled = true),
        )

    @Test fun serverTranscodePreferredBeforeOnDevice() =
        // First FORMAT failure of a direct-play attempt still tries the SERVER transcode before on-device.
        assertEquals(
            RecoveryAction.TRANSCODE_FALLBACK,
            decide(onDeviceOnlineEnabled = true),
        )
}
