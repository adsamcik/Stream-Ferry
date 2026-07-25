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

    /** A server transcode still failed; retry once at the next lower resolution cap. */
    LOWER_RESOLUTION_FALLBACK,

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
 *  - everything else (local file, already server-transcoding, or already recovered) is surfaced
 *    ([RecoveryAction.SURFACE]).
 *
 * @param alreadyTranscoding true if the current stream is already a server transcode (Cast HLS, a prior
 *   fallback, or the user forced transcode) — nothing safer to fall back to server-side.
 * @param alreadyRetried true if a same-stream network retry was already spent this session.
 * @param hasLowerResolutionFallback true when the current server stream has another quality cap to try.
 */
fun decideRecovery(
    failure: PlaybackFailureKind,
    isReloading: Boolean,
    isOnlineSession: Boolean,
    preferDirectPlay: Boolean,
    alreadyTranscoding: Boolean,
    alreadyRetried: Boolean,
    hasLowerResolutionFallback: Boolean = false,
): RecoveryAction = when {
    isReloading -> RecoveryAction.IGNORE_DURING_RELOAD
    failure == PlaybackFailureKind.NETWORK ->
        if (alreadyRetried) RecoveryAction.SURFACE else RecoveryAction.RETRY_SAME_STREAM
    isOnlineSession && preferDirectPlay && !alreadyTranscoding -> RecoveryAction.TRANSCODE_FALLBACK
    isOnlineSession && alreadyTranscoding && hasLowerResolutionFallback ->
        RecoveryAction.LOWER_RESOLUTION_FALLBACK
    else -> RecoveryAction.SURFACE
}
