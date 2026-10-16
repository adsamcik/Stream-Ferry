package com.adsamcik.streamferry.data.transcode

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Muxer
import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import com.google.common.collect.ImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HlsFragmentNormalizerTest {
    @Test
    fun `actual two track muxer fragments have relative offsets continuous clocks and preserved AV start`() {
        val normalizer = HlsFragmentNormalizer()
        var init = ByteArray(0)
        val fragments = mutableListOf<ByteArray>()
        val factory = object : Muxer.Factory {
            override fun create(path: String): Muxer = FragmentedMp4Muxer.Builder(FragmentOutput(
                { init = it; normalizer.setInitialization(it) },
                { fragments += normalizer.normalize(it) },
            )).setFragmentDurationMs(1_000).build()
            override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = ImmutableList.of()
        }
        val muxer = TimestampOriginFactory(factory) { id, firstPts -> normalizer.setTrackStart(id + 1, firstPts) }.create("unused")
        val video = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(640).setHeight(360)
            .setInitializationData(listOf(
                byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0x95.toByte(), 0xa8.toByte(), 0x28, 0x0f, 0, 0x44, 0x7a),
                byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x38, 0x80.toByte()),
            )).build())
        val audio = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(48_000)
            .setChannelCount(2).setInitializationData(listOf(byteArrayOf(0x11, 0x90.toByte()))).build())
        var audioFrame = 0
        for (frame in 0..120) {
            val videoPts = 12_020_000L + frame * 1_000_000L / 30
            while (12_000_000L + audioFrame * 1_024_000_000L / 48_000 <= videoPts) {
                val sample = byteArrayOf(1, 2)
                muxer.writeSampleData(audio, ByteBuffer.wrap(sample), BufferInfo(
                    12_000_000L + audioFrame++ * 1_024_000_000L / 48_000, sample.size, 0,
                ))
            }
            val sample = byteArrayOf(0, 0, 0, 1, if (frame % 60 == 0) 0x65 else 0x41, 1, 2, 3)
            muxer.writeSampleData(video, ByteBuffer.wrap(sample), BufferInfo(videoPts, sample.size, if (frame % 60 == 0) C.BUFFER_FLAG_KEY_FRAME else 0))
        }
        assertTrue(fragments.isNotEmpty(), "fragments must be readable while the muxer remains open")
        muxer.close()
        val clocks = Fmp4Splitter.trackTimescales(init)
        val next = mutableMapOf(1 to Math.round(0.020 * clocks.getValue(1)), 2 to 0L)
        var sawSingletonVideoTail = false
        for (fragment in fragments) {
            assertTrue(Fmp4Splitter.isValidMediaSegment(fragment))
            val moofEnd = ByteBuffer.wrap(fragment).getInt(0)
            for ((type, start, end) in boxes(fragment, 8, moofEnd)) {
                if (type != "traf") continue
                val children = boxes(fragment, start + 8, end).associateBy { it.first }
                val tfhd = children.getValue("tfhd").second
                val track = ByteBuffer.wrap(fragment).getInt(tfhd + 12)
                assertEquals(0x020000, ByteBuffer.wrap(fragment).getInt(tfhd + 8))
                val tfdt = children.getValue("tfdt").second
                assertEquals(children.getValue("tfhd").third, tfdt, "decode time follows the track header")
                assertEquals(next.getValue(track), ByteBuffer.wrap(fragment).getLong(tfdt + 12))
                val trun = children.getValue("trun").second
                assertTrue(children.getValue("tfdt").third <= trun, "decode time must be available before parsing samples")
                val count = ByteBuffer.wrap(fragment).getInt(trun + 12)
                if (track == 1 && count == 1) sawSingletonVideoTail = true
                val dataOffset = ByteBuffer.wrap(fragment).getInt(trun + 16)
                var duration = 0L
                var size = 0L
                repeat(count) { index ->
                    duration += ByteBuffer.wrap(fragment).getInt(trun + 20 + index * 12)
                    size += ByteBuffer.wrap(fragment).getInt(trun + 24 + index * 12)
                }
                assertTrue(dataOffset >= moofEnd + 8 && dataOffset + size <= fragment.size)
                next[track] = next.getValue(track) + duration
            }
        }
        assertTrue(sawSingletonVideoTail, "the final keyframe must retain a positive sample-sized duration")
        assertTrue(next.getValue(1) > clocks.getValue(1) * 4)
        assertTrue(next.getValue(2) > clocks.getValue(2) * 4)
    }

    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> = buildList {
        var cursor = from
        while (cursor < to) {
            val length = ByteBuffer.wrap(bytes).getInt(cursor)
            add(Triple(String(bytes, cursor + 4, 4), cursor, cursor + length))
            cursor += length
        }
    }
}
