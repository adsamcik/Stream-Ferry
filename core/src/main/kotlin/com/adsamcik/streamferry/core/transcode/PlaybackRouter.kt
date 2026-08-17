package com.adsamcik.streamferry.core.transcode

import com.adsamcik.streamferry.core.stream.PlayMethod
import com.adsamcik.streamferry.core.stream.StreamDecision

/** Per-source transcoding capability, used to route a [StreamDecision] to server vs client transcode. */
data class SourceCapabilities(
    /** True if the source's server can transcode (Jellyfin). False for local files (no server). */
    val canServerTranscode: Boolean,
    /** True if the source supports random access (range/seek) — required for a seekable client transcode. */
    val isSeekable: Boolean,
    /** True when the input can be opened again for later segments or recovery after a seek. */
    val isReopenable: Boolean = isSeekable,
    /** True only when this source has an implemented, authenticated input adapter for the phone transcoder. */
    val canStreamToClientTranscoder: Boolean = false,
)

enum class RouteKind {
    /** Stream the original bytes via the range proxy (no transcode). Full seek via byte ranges. */
    DIRECT_PLAY,

    /** Ask the source's server to transcode (e.g. Jellyfin server-side remux/HLS). */
    SERVER_TRANSCODE,

    /** Transcode on the phone (HW MediaCodec) into a seekable phone-hosted HLS/CMAF origin. */
    CLIENT_TRANSCODE,

    /** No current provider can safely execute the requested conversion. */
    UNSUPPORTED,
}

data class PlaybackRoute(val kind: RouteKind, val rationale: String)

/**
 * Decides HOW to deliver a stream by composing the (server-oriented) [StreamDecision] from
 * [com.adsamcik.streamferry.core.stream.StreamSelectionService] with the source's [SourceCapabilities]. This
 * ADDS client-side on-device transcode WITHOUT changing the existing server-side selection logic, so
 * Jellyfin's behavior is unchanged unless client transcode is explicitly preferred. Pure + testable.
 */
class PlaybackRouter {

    fun route(
        decision: StreamDecision,
        source: SourceCapabilities,
        preferClientTranscode: Boolean = false,
    ): PlaybackRoute {
        if (decision.playMethod == PlayMethod.DIRECT_PLAY) {
            return PlaybackRoute(
                RouteKind.DIRECT_PLAY,
                "Original is target-compatible; direct play (range proxy).",
            )
        }

        // A remux/transcode is needed (DIRECT_STREAM_REMUX / AUDIO_TRANSCODE / HLS_TRANSCODE).
        val serverPossible = source.canServerTranscode
        val clientPossible = source.isSeekable && source.isReopenable && source.canStreamToClientTranscoder
        return when {
            serverPossible && !preferClientTranscode ->
                PlaybackRoute(
                    RouteKind.SERVER_TRANSCODE,
                    "Source server can transcode; using server-side (${decision.playMethod}).",
                )

            clientPossible ->
                PlaybackRoute(
                    RouteKind.CLIENT_TRANSCODE,
                    "On-device HW transcode to a seekable phone-hosted HLS/CMAF origin.",
                )

            serverPossible ->
                PlaybackRoute(
                    RouteKind.SERVER_TRANSCODE,
                    "Client transcode needs a seekable source; falling back to server-side.",
                )

            else ->
                PlaybackRoute(
                    RouteKind.UNSUPPORTED,
                    "No eligible server or reopenable, seekable phone-transcode input provider.",
                )
        }
    }
}
