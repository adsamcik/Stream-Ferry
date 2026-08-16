package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.AudioCodec
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.core.transcode.isPackagingValid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscodeFormatsTest {

    @Test fun hevcInTransportStreamIsInvalid() {
        assertFalse(isPackagingValid(VideoCodec.HEVC, StreamContainer.HLS_TS))
    }

    @Test fun hevcInFmp4OrDashIsValid() {
        assertTrue(isPackagingValid(VideoCodec.HEVC, StreamContainer.HLS_FMP4))
        assertTrue(isPackagingValid(VideoCodec.HEVC, StreamContainer.DASH_FMP4))
    }

    @Test fun h264AnyContainerIsValid() {
        assertTrue(isPackagingValid(VideoCodec.H264, StreamContainer.HLS_TS))
        assertTrue(isPackagingValid(VideoCodec.H264, StreamContainer.HLS_FMP4))
    }

    @Test fun targetRejectsHevcOverTransportStream() {
        assertFailsWith<IllegalArgumentException> {
            TranscodeTarget(VideoCodec.HEVC, AudioCodec.AAC, StreamContainer.HLS_TS, ResolutionTier.UHD_4K, 30)
        }
    }

    @Test fun targetRejectsTenBitOnH264() {
        assertFailsWith<IllegalArgumentException> {
            TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, StreamContainer.HLS_TS, ResolutionTier.FHD_1080P, 30, tenBit = true)
        }
    }

    @Test fun validTargetConstructs() {
        val t = TranscodeTarget(VideoCodec.HEVC, AudioCodec.AAC, StreamContainer.HLS_FMP4, ResolutionTier.UHD_4K, 30, tenBit = true)
        assertEquals(ResolutionTier.UHD_4K, t.maxResolution)
        assertTrue(t.tenBit)
    }
}
