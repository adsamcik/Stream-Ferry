package com.videobridge.data.jellyfin

import com.videobridge.core.stream.Protocol
import com.videobridge.core.stream.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceProfilesTest {

    private val castCaps = TargetCapabilities(
        protocol = Protocol.CAST,
        supportedContainers = setOf("mp4"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac", "mp3"),
        supportsHevc = false,
        supports10Bit = false,
        supportsHls = true,
    )

    private val dlnaCaps = TargetCapabilities(
        protocol = Protocol.DLNA,
        supportedContainers = setOf("mp4", "mkv", "ts"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac", "ac3"),
        supportsHevc = false,
        supports10Bit = false,
        supportsHls = false,
    )

    @Test fun castUsesHlsTranscodeFallback() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = 8_000_000, forceTranscode = false, allowSubtitleBurnIn = false)
        assertTrue(json.contains("\"Protocol\":\"hls\""))
        assertTrue(json.contains("\"VideoCodec\":\"h264\""))
        assertTrue(json.contains("\"MaxStreamingBitrate\":8000000"))
        // H.264/AAC direct play is advertised; HEVC is not (it would be transcoded).
        assertTrue(json.contains("\"DirectPlayProfiles\":[{"))
        assertFalse(json.contains("hevc"))
    }

    @Test fun dlnaUsesProgressiveTranscodeFallback() {
        val json = DeviceProfiles.forTarget(dlnaCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        assertTrue(json.contains("\"Protocol\":\"http\""))
        assertFalse(json.contains("\"Protocol\":\"hls\""))
    }

    @Test fun forceTranscodeAdvertisesNoDirectPlay() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = true, allowSubtitleBurnIn = false)
        assertTrue(json.contains("\"DirectPlayProfiles\":[]"))
    }

    @Test fun non10BitTargetGetsBitDepthCodecProfile() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        assertTrue(json.contains("CodecProfiles"))
        assertTrue(json.contains("VideoBitDepth"))
    }

    @Test fun hevcCapableTargetAdvertisesHevcDirectPlay() {
        val caps = castCaps.copy(supportedVideoCodecs = setOf("h264", "hevc"), supportsHevc = true, supports10Bit = true)
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        assertTrue(json.contains("hevc")) // HEVC direct play advertised
        // The always-on h264 transcode cap is present, but a 10-bit-capable target gets NO codec-agnostic
        // bit-depth restriction, so HEVC 10-bit can still direct-play.
        assertTrue(json.contains("\"Codec\":\"h264\""))
        assertFalse(json.contains("""{"Type":"Video","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitDepth"""))
    }

    @Test fun h264TranscodeTargetIsCappedForDecodability() {
        // H.264 is only ever the transcode fallback; it must be capped to <=1080p / 8-bit / <=L4.2 so a
        // 4K/10-bit HEVC source doesn't transcode to an undecodable H.264 stream (Chromecast error 104).
        val caps = castCaps.copy(supportedVideoCodecs = setOf("h264", "hevc"), supportsHevc = true, supports10Bit = true)
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = true, allowSubtitleBurnIn = false)
        assertTrue(json.contains("\"Codec\":\"h264\""))
        assertTrue(json.contains("\"Property\":\"Width\",\"Value\":\"1920\""))
        assertTrue(json.contains("\"Property\":\"Height\",\"Value\":\"1080\""))
        assertTrue(json.contains("\"Property\":\"VideoLevel\",\"Value\":\"42\""))
        assertTrue(json.contains("\"Property\":\"VideoBitDepth\",\"Value\":\"8\""))
    }

    @Test fun hevcCapableTargetPrefersHevcTranscodeToPreserveHdr() {
        // A 4K HDR passthrough (HEVC-capable) target gets an HEVC transcode profile listed BEFORE H.264 so
        // a forced transcode keeps 10-bit HDR; H.264 remains the compatibility fallback.
        val caps = castCaps.copy(supportedVideoCodecs = setOf("h264", "hevc"), supportsHevc = true, supports10Bit = true)
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        val hevcIdx = json.indexOf("\"VideoCodec\":\"hevc\"")
        val h264Idx = json.indexOf("\"VideoCodec\":\"h264\"")
        assertTrue(hevcIdx >= 0, "HEVC transcode profile present")
        assertTrue(h264Idx >= 0, "H.264 transcode profile present as fallback")
        assertTrue(hevcIdx < h264Idx, "HEVC must be listed before H.264 so HDR is preserved when possible")
    }

    @Test fun nonHevcTargetHasOnlyH264Transcode() {
        // The conservative baseline (used on a decode-failure fallback) offers only H.264 so the transcode
        // is always decodable.
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        assertFalse(json.contains("\"VideoCodec\":\"hevc\""))
    }

    @Test fun burnInAddsEncodeSubtitleMethods() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = true)
        assertTrue(json.contains("\"Method\":\"Encode\""))
    }

    @Test fun burnInEncodesTextSubtitlesToo() {
        // Burn-in must encode EVERY format (incl. srt/vtt): external text renditions don't reach a DLNA
        // renderer through the proxy, so a user-selected subtitle would otherwise silently not appear.
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = true)
        assertTrue(json.contains("\"Format\":\"srt\",\"Method\":\"Encode\""))
        assertTrue(json.contains("\"Format\":\"vtt\",\"Method\":\"Encode\""))
        assertFalse(json.contains("\"Method\":\"External\"")) // nothing left external when burning in
    }

    @Test fun noBurnInKeepsSubtitlesExternal() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false)
        assertTrue(json.contains("\"Method\":\"External\""))
        assertFalse(json.contains("\"Method\":\"Encode\"")) // no forced transcode-for-subs when off
    }

    @Test fun av1CapableTargetOffersAv1TranscodeFirst() {
        // A TV that lists AV1 gets an AV1 transcode profile ordered before HEVC and H.264 (best-first).
        val caps = castCaps.copy(
            supportedVideoCodecs = setOf("h264", "hevc", "av1"), supportsHevc = true, supports10Bit = true,
        )
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = true, allowSubtitleBurnIn = false)
        val av1Idx = json.indexOf("\"VideoCodec\":\"av1\"")
        val hevcIdx = json.indexOf("\"VideoCodec\":\"hevc\"")
        val h264Idx = json.indexOf("\"VideoCodec\":\"h264\"")
        assertTrue(av1Idx in 0 until hevcIdx, "AV1 offered before HEVC")
        assertTrue(hevcIdx in 0 until h264Idx, "HEVC offered before H.264")
        // AV1 must be packaged in fMP4, never MPEG-TS.
        assertTrue(json.contains("{\"Container\":\"mp4\",\"Type\":\"Video\",\"Protocol\":\"hls\",\"VideoCodec\":\"av1\""))
    }

    @Test fun vp9CapableTargetOffersVp9Transcode() {
        val caps = castCaps.copy(supportedVideoCodecs = setOf("h264", "vp9"))
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = true, allowSubtitleBurnIn = false)
        val vp9Idx = json.indexOf("\"VideoCodec\":\"vp9\"")
        val h264Idx = json.indexOf("\"VideoCodec\":\"h264\"")
        assertTrue(vp9Idx in 0 until h264Idx, "VP9 offered before the H.264 fallback")
        assertTrue(json.contains("\"VideoCodec\":\"vp9\"") && json.contains("\"Container\":\"mp4\""))
    }

    @Test fun targetWithoutFancyCodecsStillOffersH264Only() {
        val json = DeviceProfiles.forTarget(castCaps, maxBitrateBps = null, forceTranscode = true, allowSubtitleBurnIn = false)
        assertFalse(json.contains("\"VideoCodec\":\"av1\""))
        assertFalse(json.contains("\"VideoCodec\":\"vp9\""))
        assertTrue(json.contains("\"VideoCodec\":\"h264\""))
    }

    @Test fun maxVideoHeightAddsCodecAgnosticHeightCap() {
        // A 1080p max caps EVERY codec's Height so the server downscales a 4K source.
        val json = DeviceProfiles.forTarget(
            castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false, maxVideoHeight = 1080,
        )
        assertTrue(json.contains("""{"Type":"Video","Conditions":[{"Condition":"LessThanEqual","Property":"Height","Value":"1080","IsRequired":false}]}"""))
    }

    @Test fun maxVideoHeightZeroAddsNoResolutionCap() {
        val json = DeviceProfiles.forTarget(
            castCaps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false, maxVideoHeight = 0,
        )
        // No codec-agnostic Height condition (only the always-on H.264 Width/Height caps remain).
        assertFalse(json.contains(""""Type":"Video","Conditions":[{"Condition":"LessThanEqual","Property":"Height""""))
    }

    @Test fun fourKMaxCapsAtTwentyOneSixtyStillAllowingFourK() {
        val caps = castCaps.copy(supportedVideoCodecs = setOf("h264", "hevc"), supportsHevc = true, supports10Bit = true)
        val json = DeviceProfiles.forTarget(caps, maxBitrateBps = null, forceTranscode = false, allowSubtitleBurnIn = false, maxVideoHeight = 2160)
        assertTrue(json.contains("\"Property\":\"Height\",\"Value\":\"2160\""))
    }
}
