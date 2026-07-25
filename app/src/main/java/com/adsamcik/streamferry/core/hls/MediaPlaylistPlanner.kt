package com.adsamcik.streamferry.core.hls

import java.util.Locale

/**
 * Plans a SEEKABLE (VOD) HLS media playlist for on-device transcoding — the mechanism that gives the TV
 * FULL seek over a live phone transcode.
 *
 * Our content is VOD (Jellyfin items, local files), so the phone advertises the WHOLE runtime as
 * keyframe-aligned segments. The TV can seek anywhere; the transcoder re-seeks the source to that
 * segment's start (nearest preceding sync sample) and transcodes it on demand. This mirrors how a
 * server-side HLS transcoder achieves seek, done on-device.
 *
 * Pure text + arithmetic, fully unit-testable. The proxy serves the playlist + (opaque, session-scoped)
 * segment URLs from the phone LAN IP; the TV never sees a source/Jellyfin URL or token.
 */
class MediaPlaylistPlanner(
    /** Nominal segment length. ~2 s balances latency, request rate, and keyframe frequency. */
    private val targetSegmentSeconds: Double = DEFAULT_SEGMENT_SECONDS,
) {
    init { require(targetSegmentSeconds > 0) { "targetSegmentSeconds must be > 0" } }

    data class Segment(val index: Int, val startSeconds: Double, val durationSeconds: Double)

    /** Even segmentation across [runtimeSeconds]; the final segment carries the remainder. */
    fun planSegments(runtimeSeconds: Double): List<Segment> {
        require(runtimeSeconds.isFinite() && runtimeSeconds >= 0.0) { "runtimeSeconds must be finite and >= 0" }
        if (runtimeSeconds == 0.0) return emptyList()
        val countAsDouble = Math.ceil(runtimeSeconds / targetSegmentSeconds)
        require(countAsDouble <= MAX_SEGMENTS.toDouble()) {
            "runtime exceeds the ${MAX_SEGMENTS}-segment on-device HLS safety limit"
        }
        val count = countAsDouble.toInt()
        return (0 until count).map { i ->
            val start = i * targetSegmentSeconds
            val duration = if (i == count - 1) runtimeSeconds - start else targetSegmentSeconds
            Segment(i, start, duration)
        }
    }

    /**
     * The segment index a seek to [positionSeconds] lands in (clamped to the valid range). Drives the
     * source re-seek so a seek anywhere resumes the transcode at the right place.
     */
    fun segmentIndexForPosition(positionSeconds: Double, runtimeSeconds: Double): Int {
        if (runtimeSeconds <= 0.0) return 0
        val clamped = positionSeconds.coerceIn(0.0, runtimeSeconds)
        val lastIndex = (planSegments(runtimeSeconds).size - 1).coerceAtLeast(0)
        return Math.floor(clamped / targetSegmentSeconds).toInt().coerceIn(0, lastIndex)
    }

    /**
     * Build a VOD HLS media playlist. [segmentUri] maps a segment index to its (opaque, proxy-scoped)
     * URI. When [fmp4] is true an `EXT-X-MAP` init segment is emitted via [initUri] (required for fMP4).
     */
    fun buildVodPlaylist(
        segments: List<Segment>,
        fmp4: Boolean,
        segmentUri: (Int) -> String,
        initUri: (() -> String)? = null,
    ): String {
        require(segments.size <= MAX_SEGMENTS) { "too many HLS segments" }
        require(segments.all { it.durationSeconds.isFinite() && it.durationSeconds > 0.0 }) {
            "HLS segment durations must be finite and positive"
        }
        val maxDuration = segments.maxOfOrNull { it.durationSeconds } ?: targetSegmentSeconds
        val target = Math.ceil(maxDuration).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:").append(if (fmp4) 7 else 3).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        if (fmp4) {
            val init = initUri?.invoke()
                ?: throw IllegalArgumentException("fMP4 playlist requires an init segment URI")
            sb.append("#EXT-X-MAP:URI=\"").append(init).append("\"\n")
        }
        for (segment in segments) {
            sb.append("#EXTINF:").append(formatSeconds(segment.durationSeconds)).append(",\n")
            sb.append(segmentUri(segment.index)).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    private fun formatSeconds(value: Double): String = String.format(Locale.US, "%.3f", value)

    /**
     * Build a MASTER (multivariant) HLS playlist pointing at the single media playlist [mediaUri],
     * declaring the stream's [codecs] (RFC 6381), [width]x[height] and an approximate [bandwidthBps].
     * A Cast/CMAF receiver needs the `CODECS` attribute to initialize its fMP4 pipeline — handed a bare
     * media playlist with fMP4 segments and no codec declaration it typically buffers forever without
     * ever starting playback. [codecs] null omits the attribute (last-resort; may still not start).
     */
    fun buildMasterPlaylist(
        mediaUri: String,
        codecs: String?,
        width: Int?,
        height: Int?,
        bandwidthBps: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidthBps.coerceAtLeast(1))
        if (!codecs.isNullOrBlank()) sb.append(",CODECS=\"").append(codecs).append('"')
        if (width != null && height != null && width > 0 && height > 0) {
            sb.append(",RESOLUTION=").append(width).append('x').append(height)
        }
        sb.append('\n')
        sb.append(mediaUri).append('\n')
        return sb.toString()
    }

    companion object {
        const val DEFAULT_SEGMENT_SECONDS = 2.0
        /** 24 h at the default two-second cadence; prevents overflow/unbounded manifest allocation. */
        const val MAX_SEGMENTS = 43_200
    }
}
