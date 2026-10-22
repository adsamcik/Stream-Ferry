package com.adsamcik.streamferry.data.transcode

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.MuxerException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class Media3TsMuxerTest {
    @Test
    fun `audio may be delivered first and track formats are available before output begins`() {
        val packets = mutableListOf<ByteArray>()
        val muxer = Media3TsMuxer.Factory(packets::add).create("unused.ts")
        val audioTrack = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setSampleRate(48_000).setChannelCount(2).setInitializationData(listOf(byteArrayOf(0x11, 0x90.toByte()))).build())
        muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        assertTrue(packets.isEmpty())
        val input = ByteBuffer.wrap(byteArrayOf(99, 88, 11, 22, 33, 77)).apply { position(2); limit(5) }
        muxer.writeSampleData(audioTrack, input, BufferInfo(0, 3, 0))
        assertTrue(packets.isNotEmpty())
        val audio = packets.single { (it[1].toInt() and 31) == 1 && it[2] == 1.toByte() }
        val start = 5 + (audio[4].toInt() and 0xff)
        assertContentEquals(byteArrayOf(11, 22, 33), audio.copyOfRange(start + 14 + 7, audio.size))
        muxer.close()
    }

    @Test
    fun `consumer failures are propagated and closing never publishes an extra sample`() {
        var closes = 0
        val muxer = Media3TsMuxer.Factory({ throw IllegalStateException("stream stopped") }, { closes++ }).create("unused.ts")
        val track = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        assertFailsWith<MuxerException> {
            muxer.writeSampleData(track, ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x65, 1)), BufferInfo(0, 5, C.BUFFER_FLAG_KEY_FRAME))
        }
        muxer.close()
        muxer.close()
        assertEquals(1, closes)
        assertFailsWith<MuxerException> {
            muxer.writeSampleData(track, ByteBuffer.allocate(0), BufferInfo(0, 0, C.BUFFER_FLAG_END_OF_STREAM))
        }
    }
}
