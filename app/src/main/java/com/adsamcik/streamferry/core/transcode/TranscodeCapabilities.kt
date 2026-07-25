package com.adsamcik.streamferry.core.transcode

/**
 * What THIS phone's hardware video encoders can sustain in realtime, expressed as the highest tier the
 * phone can encode at >= [maxFps] in hardware. Filled in by the Android layer by probing
 * `MediaCodecList` / `VideoCapabilities` / supported PerformancePoints (hardware-accelerated codecs
 * only — software encode is not realtime-capable). Pure + testable here.
 *
 * A null tier means the phone cannot realtime-encode that codec at all.
 */
data class DeviceEncodeCapabilities(
    val h264MaxResolution: ResolutionTier?,
    val hevcMaxResolution: ResolutionTier?,
    /** HEVC Main10 (10-bit) hardware encode, requiring HEVCProfileMain10 + COLOR_FormatYUVP010. */
    val hevcMain10: Boolean = false,
    val maxFps: Int = 30,
) {
    private fun hwTier(codec: VideoCodec): ResolutionTier? = when (codec) {
        VideoCodec.H264 -> h264MaxResolution
        VideoCodec.HEVC -> hevcMaxResolution
        VideoCodec.VP9, VideoCodec.AV1 -> null
    }

    /** True if the phone can HARDWARE-encode [codec] at [tier] in realtime. */
    fun canEncodeHardware(codec: VideoCodec, tier: ResolutionTier): Boolean {
        val cap = hwTier(codec) ?: return false
        return tier.maxHeightPx <= cap.maxHeightPx
    }

    /** Backwards-compatible hardware-encode check (used by the legacy [TranscodeNegotiator]). */
    fun canEncode(codec: VideoCodec, tier: ResolutionTier): Boolean = canEncodeHardware(codec, tier)
}

/**
 * What the Cast/DLNA receiver can play. From a Custom Web Receiver's `canDisplayType()` when available,
 * otherwise conservative defaults (H.264 up to 1080p, classic HLS) with playback-failure fallback. A
 * null HEVC tier means the receiver cannot play HEVC.
 */
data class ReceiverPlaybackCapabilities(
    val h264MaxResolution: ResolutionTier = ResolutionTier.FHD_1080P,
    val hevcMaxResolution: ResolutionTier? = null,
    val vp9MaxResolution: ResolutionTier? = null,
    val av1MaxResolution: ResolutionTier? = null,
    /** Receiver can render >8-bit / HDR (10-bit) video. */
    val tenBit: Boolean = false,
    val supportsFmp4: Boolean = true,
    val supportsTs: Boolean = true,
    val supportsDash: Boolean = false,
) {
    private fun tier(codec: VideoCodec): ResolutionTier? = when (codec) {
        VideoCodec.H264 -> h264MaxResolution
        VideoCodec.HEVC -> hevcMaxResolution
        VideoCodec.VP9 -> vp9MaxResolution
        VideoCodec.AV1 -> av1MaxResolution
    }

    fun canPlay(codec: VideoCodec, tier: ResolutionTier): Boolean {
        val cap = tier(codec) ?: return false
        return tier.maxHeightPx <= cap.maxHeightPx
    }

    /** Codecs the receiver can play at all (any tier), in [VideoCodec] declaration order (best-first). */
    fun playableCodecs(): List<VideoCodec> = VideoCodec.entries.filter { tier(it) != null }

    companion object {
        /** The safe default used with the Default/Styled Media Receiver before any capability probe. */
        val CONSERVATIVE = ReceiverPlaybackCapabilities()
    }
}
