package com.adsamcik.streamferry.core.transcode

/**
 * Chooses the on-device transcode [TranscodeTarget] by gating BOTH the phone's hardware encoders AND
 * the receiver, following the ladder HEVC-4K -> H.264-4K -> (HEVC/H.264)-1080p -> H.264-720p (all with
 * AAC audio). 4K is negotiated, never assumed; the safe default is 1080p H.264/AAC. HEVC is only ever
 * packaged as fMP4/CMAF or DASH (never MPEG-TS). Pure + unit-testable.
 */
class TranscodeNegotiator {

    /**
     * The phone publishes HLS/fMP4 to capable receivers and a completed MP4 to the others. It limits
     * output to the Media3 Transformer codecs it can request consistently (H.264 and HEVC); AV1/VP9, DASH,
     * MPEG-TS, Main10, explicit bitrate, and fixed-frame-rate contracts remain feature-gated.
     */
    fun negotiate(
        device: DeviceEncodeCapabilities,
        receiver: ReceiverPlaybackCapabilities,
        sourceMaxResolution: ResolutionTier = ResolutionTier.UHD_4K,
        prefer4k: Boolean = true,
        /** Optional user-selected codec; its tier is still negotiated against both endpoints. */
        preferredCodec: VideoCodec? = null,
    ): TranscodeTarget {
        val fpsAdmissionBound = device.maxFps.coerceAtLeast(1)
        val ladder = buildList {
            for (tier in ResolutionTier.entries.reversed()) {
                if (tier.maxHeightPx > sourceMaxResolution.maxHeightPx) continue
                if (!prefer4k && tier.maxHeightPx > ResolutionTier.FHD_1080P.maxHeightPx) continue
                add(VideoCodec.HEVC to tier)
                add(VideoCodec.H264 to tier)
            }
        }

        for ((codec, tier) in ladder) {
            if (preferredCodec != null && codec != preferredCodec) continue
            if (codec == VideoCodec.HEVC && receiver.hevcMaxResolution == null) continue
            if (!device.canEncodeHardware(codec, tier)) continue
            if (!receiver.canPlay(codec, tier)) continue
            // maxFps is an encoder-capability admission bound, not a Media3 output request: do not infer
            // that the produced bitstream has a fixed frame rate or bit depth from this target.
            return TranscodeTarget(
                videoCodec = codec,
                audioCodec = AudioCodec.AAC,
                container = if (receiver.supportsFmp4) StreamContainer.HLS_FMP4 else StreamContainer.MP4,
                maxResolution = tier,
                maxFps = fpsAdmissionBound,
                tenBit = false,
            )
        }

        // Do not manufacture a nominal H.264 floor when the phone has no admitted hardware encoder.
        // PlaybackEngine catches this and leaves the local file on the direct-play path instead of
        // starting a Transformer job that cannot possibly be fulfilled.
        throw IllegalStateException(
            "No compatible hardware H.264/HEVC target is available" +
                preferredCodec?.let { " for the selected $it codec" }.orEmpty() + ".",
        )
    }

}
