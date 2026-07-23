package com.videobridge.core.transcode

/**
 * Chooses the on-device transcode [TranscodeTarget] by gating BOTH the phone's hardware encoders AND
 * the receiver, following the ladder HEVC-4K -> H.264-4K -> (HEVC/H.264)-1080p -> H.264-720p (all with
 * AAC audio). 4K is negotiated, never assumed; the safe default is 1080p H.264/AAC. HEVC is only ever
 * packaged as fMP4/CMAF or DASH (never MPEG-TS). Pure + unit-testable.
 */
class TranscodeNegotiator {

    fun negotiate(
        device: DeviceEncodeCapabilities,
        receiver: ReceiverPlaybackCapabilities,
        sourceMaxResolution: ResolutionTier = ResolutionTier.UHD_4K,
        prefer4k: Boolean = true,
    ): TranscodeTarget {
        val fps = device.maxFps.coerceAtLeast(1)
        val fourK = minTier(ResolutionTier.UHD_4K, sourceMaxResolution)
        val fhd = minTier(ResolutionTier.FHD_1080P, sourceMaxResolution)
        val hd = minTier(ResolutionTier.HD_720P, sourceMaxResolution)

        val ladder = buildList {
            if (prefer4k) {
                add(VideoCodec.HEVC to fourK)
                add(VideoCodec.H264 to fourK)
            }
            add(VideoCodec.HEVC to fhd)
            add(VideoCodec.H264 to fhd)
            add(VideoCodec.H264 to hd)
        }

        for ((codec, tier) in ladder) {
            if (codec == VideoCodec.HEVC && receiver.hevcMaxResolution == null) continue
            if (!device.canEncode(codec, tier)) continue
            if (!receiver.canPlay(codec, tier)) continue
            val container = chooseContainer(codec, receiver) ?: continue
            val tenBit = codec == VideoCodec.HEVC && device.hevcMain10
            return TranscodeTarget(codec, AudioCodec.AAC, container, tier, fps, tenBit)
        }

        // Guaranteed best-effort floor: H.264 @ the smallest tier. The Android layer attempts it and
        // degrades on playback failure. Prefer TS (most universally supported by the default receiver),
        // else fMP4.
        val floor = if (receiver.supportsFmp4 && !receiver.supportsTs) StreamContainer.HLS_FMP4
        else StreamContainer.HLS_TS
        return TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, floor, hd, fps)
    }

    /** Pick a receiver-supported container for [codec], honoring the H.264-only-TS rule. Null if none. */
    private fun chooseContainer(codec: VideoCodec, receiver: ReceiverPlaybackCapabilities): StreamContainer? =
        when (codec) {
            // H.264: TS first (most compatible with the default receiver), else fMP4.
            VideoCodec.H264 -> when {
                receiver.supportsTs -> StreamContainer.HLS_TS
                receiver.supportsFmp4 -> StreamContainer.HLS_FMP4
                else -> null
            }
            // HEVC/VP9/AV1: fMP4/CMAF preferred, DASH next; never TS.
            VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1 -> when {
                receiver.supportsFmp4 -> StreamContainer.HLS_FMP4
                receiver.supportsDash -> StreamContainer.DASH_FMP4
                else -> null
            }
        }

    private fun minTier(a: ResolutionTier, b: ResolutionTier): ResolutionTier =
        if (a.maxHeightPx <= b.maxHeightPx) a else b
}
