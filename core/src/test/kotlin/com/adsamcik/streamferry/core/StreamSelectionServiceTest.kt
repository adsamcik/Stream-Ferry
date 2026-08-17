package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.stream.AudioTrackSelection
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.PlayMethod
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.StreamSelectionService
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamSelectionServiceTest {

    private val svc = StreamSelectionService()

    private val chromecastH264 = TargetCapabilities(
        protocol = Protocol.CAST,
        supportedContainers = setOf("mp4"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac"),
        supportsHevc = false,
        supports10Bit = false,
        supportedExternalSubtitleFormats = setOf("vtt"),
    )

    private fun media(
        container: String = "mp4", v: String = "h264", a: String = "aac",
        bitDepth: Int = 8, hdr: Boolean = false, sub: String? = null, bitrate: Long? = null,
    ) = MediaProfile(container, v, audioCodec = a, bitDepth = bitDepth, isHdr = hdr, subtitleFormat = sub, videoBitrateBps = bitrate)

    @Test fun compatibleMp4_directPlay() {
        val d = svc.select(chromecastH264, media(), StreamPreferences())
        assertEquals(PlayMethod.DIRECT_PLAY, d.playMethod)
        assertFalse(d.producesHls)
    }

    @Test fun broadCastProfileDirectPlaysHevcAc3WhereConservativeTranscodes() {
        // The optimistic Cast profile (as used by the direct-play-first path) advertises HEVC/AC3/10-bit,
        // so a modern TV that can decode them streams the ORIGINAL instead of a full transcode...
        val broad = TargetCapabilities(
            protocol = Protocol.CAST,
            supportedContainers = setOf("mp4", "mkv", "ts"),
            supportedVideoCodecs = setOf("h264", "hevc", "vp9", "av1"),
            supportedAudioCodecs = setOf("aac", "ac3", "eac3", "opus"),
            supportsHevc = true,
            supports10Bit = true,
        )
        val hevc10BitAc3 = media(container = "mkv", v = "hevc", a = "ac3", bitDepth = 10, hdr = true)
        assertEquals(PlayMethod.DIRECT_PLAY, svc.select(broad, hevc10BitAc3, StreamPreferences()).playMethod)
        // ...while the safe conservative profile (the transcode fallback) full-transcodes the same source.
        assertEquals(PlayMethod.HLS_TRANSCODE, svc.select(chromecastH264, hevc10BitAc3, StreamPreferences()).playMethod)
    }

    @Test fun mkvWithCompatibleCodecs_remux() {
        val d = svc.select(chromecastH264, media(container = "mkv"), StreamPreferences())
        assertEquals(PlayMethod.DIRECT_STREAM_REMUX, d.playMethod)
    }

    @Test fun incompatibleAudio_audioTranscode() {
        val d = svc.select(chromecastH264, media(a = "ac3"), StreamPreferences())
        assertEquals(PlayMethod.AUDIO_TRANSCODE, d.playMethod)
    }

    @Test fun hevc_unsupported_hlsTranscode() {
        val d = svc.select(chromecastH264, media(v = "hevc"), StreamPreferences())
        assertEquals(PlayMethod.HLS_TRANSCODE, d.playMethod)
    }

    @Test fun hdr10bit_unsupported_hlsTranscode() {
        val d = svc.select(chromecastH264, media(bitDepth = 10, hdr = true), StreamPreferences())
        assertEquals(PlayMethod.HLS_TRANSCODE, d.playMethod)
    }

    @Test fun forceTranscode_overrides() {
        val d = svc.select(chromecastH264, media(), StreamPreferences(forceTranscode = true))
        assertEquals(PlayMethod.HLS_TRANSCODE, d.playMethod)
    }

    @Test fun pgsSubtitle_forcesBurnIn() {
        val d = svc.select(chromecastH264, media(sub = "pgs"), StreamPreferences())
        assertEquals(PlayMethod.HLS_TRANSCODE, d.playMethod)
        assertTrue(d.burnInSubtitles)
    }

    @Test fun assSubtitle_burnInOnlyIfAllowed() {
        val noBurn = svc.select(chromecastH264, media(sub = "ass"), StreamPreferences(allowSubtitleBurnIn = false))
        assertEquals(PlayMethod.DIRECT_PLAY, noBurn.playMethod)
        assertFalse(noBurn.burnInSubtitles)

        val burn = svc.select(chromecastH264, media(sub = "ass"), StreamPreferences(allowSubtitleBurnIn = true))
        assertEquals(PlayMethod.HLS_TRANSCODE, burn.playMethod)
        assertTrue(burn.burnInSubtitles)
    }

    @Test fun srtSubtitle_stillDirectPlay() {
        val d = svc.select(chromecastH264, media(sub = "srt"), StreamPreferences())
        assertEquals(PlayMethod.DIRECT_PLAY, d.playMethod)
        assertFalse(d.burnInSubtitles)
    }

    @Test fun bitrateOverLimit_transcodes() {
        val d = svc.select(
            chromecastH264, media(bitrate = 50_000_000),
            StreamPreferences(maxBitrateBps = 8_000_000),
        )
        assertEquals(PlayMethod.HLS_TRANSCODE, d.playMethod)
    }

    // ----- AudioTrackSelection: a non-default audio track can't be honored on direct play -----

    @Test fun audioSelection_nonDefaultTrack_requiresTranscode() {
        // Picking a track that isn't the server default must force a server transcode so the chosen
        // audio is muxed into the stream (the proxy can't switch embedded tracks on the TV).
        assertTrue(AudioTrackSelection.requiresServerTranscode(selectedIndex = 3, defaultIndex = 1))
    }

    @Test fun audioSelection_defaultTrackOrNone_directPlays() {
        // Selecting the default track, or clearing the selection, needs no transcode — direct play
        // already delivers that audio.
        assertFalse(AudioTrackSelection.requiresServerTranscode(selectedIndex = 1, defaultIndex = 1))
        assertFalse(AudioTrackSelection.requiresServerTranscode(selectedIndex = null, defaultIndex = 1))
    }

    @Test fun audioSelection_unknownDefault_forcesTranscodeToHonorChoice() {
        // If the default track isn't known yet, honor an explicit choice by transcoding (safe default).
        assertTrue(AudioTrackSelection.requiresServerTranscode(selectedIndex = 2, defaultIndex = null))
        assertFalse(AudioTrackSelection.requiresServerTranscode(selectedIndex = null, defaultIndex = null))
    }

    // ----- max resolution / 4K-HDR passthrough -----

    @Test fun allow4kHdrOnlyWhenMaxResolutionAllows4k() {
        assertTrue(StreamPreferences(maxVideoHeight = 2160).allow4kHdr)
        assertFalse(StreamPreferences(maxVideoHeight = 1080).allow4kHdr)
        assertFalse(StreamPreferences(maxVideoHeight = 720).allow4kHdr)
        // Default is 4K.
        assertTrue(StreamPreferences().allow4kHdr)
    }
}
