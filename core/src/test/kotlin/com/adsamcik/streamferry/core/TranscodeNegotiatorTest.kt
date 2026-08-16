package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.ReceiverPlaybackCapabilities
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeNegotiator
import com.adsamcik.streamferry.core.transcode.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        // The active client pipeline emits fMP4 only, even when a receiver also supports TS.
        assertEquals(StreamContainer.HLS_FMP4, target.container)
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
        assertEquals(false, target.tenBit) // Main10 is not an enforced Transformer output contract yet.
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

    @Test fun selectedCodecRenegotiatesToItsOwnSafeTier() {
        val device = capableDevice.copy(h264MaxResolution = ResolutionTier.FHD_1080P)
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = true,
        )
        val target = negotiator.negotiate(device, receiver, preferredCodec = VideoCodec.H264)
        assertEquals(VideoCodec.H264, target.videoCodec)
        assertEquals(ResolutionTier.FHD_1080P, target.maxResolution)
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

    @Test fun receiverWithoutFmp4IsRejectedRatherThanAdvertisingTransportStream() {
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.UHD_4K,
            hevcMaxResolution = ResolutionTier.UHD_4K,
            supportsFmp4 = false,
            supportsTs = true,
            supportsDash = false,
        )
        assertFailsWith<IllegalArgumentException> { negotiator.negotiate(capableDevice, receiver) }
    }

    @Test fun deviceWithoutH264OrHevcHardwareIsRejected() {
        val device = DeviceEncodeCapabilities(h264MaxResolution = null, hevcMaxResolution = null)
        val receiver = ReceiverPlaybackCapabilities(
            h264MaxResolution = ResolutionTier.FHD_1080P,
            supportsFmp4 = true,
        )
        assertFailsWith<IllegalStateException> { negotiator.negotiate(device, receiver) }
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
