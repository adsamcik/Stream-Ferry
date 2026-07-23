package com.videobridge.core

import com.videobridge.core.transcode.DeviceEncodeCapabilities
import com.videobridge.core.transcode.ReceiverPlaybackCapabilities
import com.videobridge.core.transcode.ResolutionTier
import com.videobridge.core.transcode.StreamContainer
import com.videobridge.core.transcode.TranscodeNegotiator
import com.videobridge.core.transcode.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TranscodeNegotiatorTest {

    private val negotiator = TranscodeNegotiator()

    private val capableDevice = DeviceEncodeCapabilities(
        h264MaxResolution = ResolutionTier.UHD_4K,
        hevcMaxResolution = ResolutionTier.UHD_4K,
        hevcMain10 = true,
        maxFps = 30,
    )

    @Test fun conservativeReceiverGets1080pH264() {
        val target = negotiator.negotiate(capableDevice, ReceiverPlaybackCapabilities.CONSERVATIVE)
        assertEquals(VideoCodec.H264, target.videoCodec)
        assertEquals(ResolutionTier.FHD_1080P, target.maxResolution)
        // Conservative receiver supports TS -> H.264 packaged as classic HLS-TS.
        assertEquals(StreamContainer.HLS_TS, target.container)
    }

    @Test fun hevc4kWhenBothSidesCapable() {
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = true,
        )
        val target = negotiator.negotiate(capableDevice, receiver)
        assertEquals(VideoCodec.HEVC, target.videoCodec)
        assertEquals(ResolutionTier.UHD_4K, target.maxResolution)
        assertEquals(StreamContainer.HLS_FMP4, target.container)
        assertEquals(true, target.tenBit)
    }

    @Test fun fallsBackToH264_4kWhenDeviceCannotHevc() {
        val device = capableDevice.copy(hevcMaxResolution = null)
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = true,
            supportsTs = true,
        )
        val target = negotiator.negotiate(device, receiver)
        assertEquals(VideoCodec.H264, target.videoCodec)
        assertEquals(ResolutionTier.UHD_4K, target.maxResolution)
    }

    @Test fun prefer4kFalseNeverPicks4k() {
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = true,
        )
        val target = negotiator.negotiate(capableDevice, receiver, prefer4k = false)
        assertNotEquals(ResolutionTier.UHD_4K, target.maxResolution)
        assertEquals(ResolutionTier.FHD_1080P, target.maxResolution)
    }

    @Test fun deviceCappedAt1080pStaysAt1080pEvenWith4kReceiver() {
        val device = DeviceEncodeCapabilities(
            h264MaxResolution = ResolutionTier.FHD_1080P,
            hevcMaxResolution = null,
        )
        val receiver = ReceiverPlaybackCapabilities(h264MaxResolution = ResolutionTier.UHD_4K)
        val target = negotiator.negotiate(device, receiver)
        assertEquals(ResolutionTier.FHD_1080P, target.maxResolution)
    }

    @Test fun hevcSkippedWhenReceiverCannotPackageItWithoutTs() {
        // Receiver can play HEVC 4k but supports ONLY transport-stream (no fMP4/DASH). HEVC must never
        // be TS, so HEVC is skipped and we fall back to H.264.
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = false,
            supportsTs = true,
            supportsDash = false,
        )
        val target = negotiator.negotiate(capableDevice, receiver)
        assertEquals(VideoCodec.H264, target.videoCodec)
        assertEquals(StreamContainer.HLS_TS, target.container)
    }

    @Test fun clampsToSourceResolution() {
        // A 1080p source must not be upscaled to 4k even when both sides could do 4k.
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = true,
        )
        val target = negotiator.negotiate(capableDevice, receiver, sourceMaxResolution = ResolutionTier.FHD_1080P)
        assertEquals(ResolutionTier.FHD_1080P, target.maxResolution)
    }
}
