package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.PlaybackFailureKind

/** What [PlaybackEngine] should do about a renderer playback failure. */
enum class RecoveryAction {
    /** Mid-reload (transcode fallback / HLS seek / bitrate switch): the error is from the dying old
     *  stream, so ignore it — reacting would abort the reload or flash a spurious error. */
    IGNORE_DURING_RELOAD,

    /** A transient network failure: reload the SAME stream once from the current position. */
    RETRY_SAME_STREAM,

    /** The renderer can't decode the original: re-resolve once with a server transcode and retry
     *  (Cast -> HLS, DLNA -> progressive TS). Online sessions only — a local file has no server. */
    TRANSCODE_FALLBACK,

    /** Server transcode is exhausted (or can't produce a codec the TV needs): transcode the online source
     *  ON THE PHONE as a last resort. Online sessions only, and only when the user opted in. */
    ONDEVICE_TRANSCODE_FALLBACK,

    /** No recovery path left: surface the error to the user. */
    SURFACE,
}

/**
 * Pure policy deciding how [PlaybackEngine] recovers from a renderer error (extracted so it can be
 * unit-tested without the Android renderer stack). Applies uniformly to Cast and DLNA:
 *
 *  - while a reload is in flight, any error is from the old stream -> [RecoveryAction.IGNORE_DURING_RELOAD];
 *  - a [PlaybackFailureKind.NETWORK] blip is retried once on the same stream ([RecoveryAction.RETRY_SAME_STREAM])
 *    — transcoding wouldn't fix connectivity;
 *  - a decode/format failure (or an [PlaybackFailureKind.UNKNOWN] one, treated optimistically the same way)
 *    on an online, direct-play session that isn't already transcoding falls back once to a server transcode
 *    ([RecoveryAction.TRANSCODE_FALLBACK]);
 *  - if server transcode is ALSO exhausted (already transcoding) and the user enabled on-device transcode
 *    for online sources, fall back to transcoding on the phone ([RecoveryAction.ONDEVICE_TRANSCODE_FALLBACK]);
 *  - everything else (local file, on-device already attempted/disabled, already recovered) is
 *    surfaced ([RecoveryAction.SURFACE]).
 *
 * @param alreadyTranscoding true if the current stream is already a server transcode (Cast HLS, a prior
 *   fallback, or the user forced transcode) — nothing safer to fall back to server-side.
 * @param alreadyRetried true if a same-stream network retry was already spent this session.
 * @param onDeviceOnlineEnabled true if the user opted into on-device transcode for online sources.
 * @param onDeviceAlreadyAttempted true if an on-device transcode fallback was already tried this session.
 * @param receiverSupportsHls true if the receiver can play the phone-hosted HLS/CMAF the on-device
 *   transcoder produces (Cast can; a baseline DLNA renderer cannot, so on-device fallback is pointless
 *   there and would only replace a clear failure with an HLS-over-DLNA hang / UPnP 701).
 */
fun decideRecovery(
    failure: PlaybackFailureKind,
    isReloading: Boolean,
    isOnlineSession: Boolean,
    preferDirectPlay: Boolean,
    alreadyTranscoding: Boolean,
    alreadyRetried: Boolean,
    onDeviceOnlineEnabled: Boolean = false,
    onDeviceAlreadyAttempted: Boolean = false,
    receiverSupportsHls: Boolean = true,
): RecoveryAction = when {
    isReloading -> RecoveryAction.IGNORE_DURING_RELOAD
    failure == PlaybackFailureKind.NETWORK ->
        if (alreadyRetried) RecoveryAction.SURFACE else RecoveryAction.RETRY_SAME_STREAM
    isOnlineSession && preferDirectPlay && !alreadyTranscoding -> RecoveryAction.TRANSCODE_FALLBACK
    // Server transcode didn't fix it (or the server can't produce the needed codec): try the phone, if opted
    // in AND the receiver can actually play the phone's HLS output (else it's a guaranteed dead end).
    isOnlineSession && onDeviceOnlineEnabled && receiverSupportsHls && !onDeviceAlreadyAttempted ->
        RecoveryAction.ONDEVICE_TRANSCODE_FALLBACK
    else -> RecoveryAction.SURFACE
}
