package com.adsamcik.streamferry.core.transcode

/**
 * Target formats for on-device (client-side) transcoding.
 *
 * Pure value types plus the Cast/HLS packaging rules. The Android MediaCodec / muxer layer maps these
 * to concrete encoder configs; this stays framework-free and unit-tested. The phone hosts the resulting
 * stream as a LAN-IP HLS/CMAF origin the TV fetches — the TV never sees a source/Jellyfin URL.
 */

/** Video codecs the on-device encoder / server can target, roughly ordered by efficiency (best first). */
enum class VideoCodec { AV1, HEVC, VP9, H264 }

/** Audio codecs the on-device encoder can target. */
enum class AudioCodec { AAC }

/** Streaming container/packaging for the phone-hosted live origin the TV fetches. */
enum class StreamContainer {
    /**
     * Classic HLS with MPEG-2 transport-stream segments. H.264 only: HEVC/VP9/AV1 must NOT be packaged in
     * MPEG-TS (and Android has no built-in TS muxer).
     */
    HLS_TS,

    /** HLS with fragmented-MP4 / CMAF segments. Works for H.264, HEVC and AV1; preferred for non-H.264. */
    HLS_FMP4,

    /** MPEG-DASH with fragmented-MP4 segments (used for VP9/AV1 where CMAF-HLS isn't accepted). */
    DASH_FMP4,
}

/**
 * A resolution tier. Negotiation works in tiers (not exact pixels); the encoder is configured to the
 * source's real dimensions capped to the chosen tier's height.
 */
enum class ResolutionTier(val maxHeightPx: Int) {
    SD_480P(480),
    HD_720P(720),
    FHD_1080P(1080),
    UHD_4K(2160),
}

/**
 * A fully-resolved transcode target chosen by [TranscodeNegotiator]. Construction enforces the packaging
 * rule (only H.264 may use MPEG-TS) and the 10-bit rule (H.264 is kept 8-bit).
 */
data class TranscodeTarget(
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val container: StreamContainer,
    val maxResolution: ResolutionTier,
    /** Encoder capability-admission bound; the active Media3 path does not force output frame rate. */
    val maxFps: Int,
    /** Requested only by broader planners; the active on-device fMP4 path feature-gates Main10. */
    val tenBit: Boolean = false,
) {
    init {
        require(maxFps >= 1) { "maxFps must be >= 1" }
        require(isPackagingValid(videoCodec, container)) {
            "Only H.264 may be packaged in MPEG-TS; use fMP4/CMAF or DASH for HEVC/VP9/AV1."
        }
        require(!tenBit || videoCodec != VideoCodec.H264) { "10-bit is not used for the H.264 fallback." }
    }
}

/** Packaging rule: only H.264 may go in MPEG-TS; HEVC/VP9/AV1 use fMP4/CMAF (or DASH). */
fun isPackagingValid(codec: VideoCodec, container: StreamContainer): Boolean = when (codec) {
    VideoCodec.H264 -> true
    VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1 -> container != StreamContainer.HLS_TS
}
