package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.transcode.AudioCodec
import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClientTranscodeSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val transcoder = mockk<OnDeviceTranscoder>()
    private val target = TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, StreamContainer.HLS_FMP4, ResolutionTier.HD_720P, 30)

    private fun session(outputs: Map<Int, ByteArray>): ClientTranscodeSession {
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        coEvery { transcoder.transcodeSegment(any(), any(), any(), any(), any(), any(), any()) } answers {
            val index = (arg<Long>(1) / 2_000L).toInt()
            arg<File>(4).writeBytes(outputs.getValue(index))
        }
        return ClientTranscodeSession(
            transcoder, "/data/local/tmp/input.mp4", 6.0, target, File(temporary.root, "session"), mockk<DiagnosticSink>(relaxed = true),
        )
    }

    @Test
    fun `HEAD never exports and out of order seeks retain each clips original init and media`() {
        val outputs = (0..2).associateWith { clip(it) }
        val session = session(outputs)
        try {
            session.playlist("http://phone/session/test", allowExport = false)
            assertEquals(ClientTranscodeSource.Resource.Unavailable, session.initSegment(2, allowExport = false))
            assertEquals(ClientTranscodeSource.Resource.Unavailable, session.mediaSegment(2, allowExport = false))
            coVerify(exactly = 0) { transcoder.transcodeSegment(any(), any(), any(), any(), any(), any(), any()) }
            for (index in listOf(0, 2, 1)) {
                val media = assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(index, allowExport = true)).bytes
                val init = assertIs<ClientTranscodeSource.Resource.Ready>(session.initSegment(index, allowExport = false)).bytes
                // Includes that clip's own edit list and its original tfdt/mfhd. AAC duration deliberately
                // does not land on the 2-second boundary; the discontinuity preserves that valid export.
                assertContentEquals(Fmp4Splitter.initSegment(outputs.getValue(index)), init)
                assertContentEquals(Fmp4Splitter.mediaSegment(outputs.getValue(index)), media)
                assertContentEquals(outputs.getValue(index), init + media)
            }
            coVerify(exactly = 3) { transcoder.transcodeSegment(any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            session.release()
        }
    }

    @Test
    fun `oversized pair is refused instead of caching init separately from media`() {
        val session = session(mapOf(0 to clip(0, mediaBytes = 25 * 1024 * 1024)))
        try {
            assertEquals(ClientTranscodeSource.Resource.Unavailable, session.mediaSegment(0, allowExport = true))
            assertEquals(ClientTranscodeSource.Resource.Unavailable, session.initSegment(0, allowExport = false))
            assertEquals(ClientTranscodeSource.Resource.Unavailable, session.mediaSegment(0, allowExport = false))
        } finally {
            session.release()
        }
    }

    private fun clip(index: Int, mediaBytes: Int = 8): ByteArray {
        val videoEntry = ByteArray(78).also {
            it[24] = 2; it[25] = 0x80.toByte() // 640
            it[26] = 1; it[27] = 0x68 // 360
        }
        fun track(id: Int, timescale: Long, entry: ByteArray): ByteArray = box(
            "trak", box("tkhd", ByteArray(12) + u32(id.toLong())) + box("edts", byteArrayOf(index.toByte())) +
                box("mdia", box("mdhd", ByteArray(12) + u32(timescale) + u32(0)) +
                    box("minf", box("stbl", box("stsd", ByteArray(4) + u32(1) + entry)))),
        )
        val init = box("ftyp", ByteArray(4)) + box("moov",
            track(1, 90_000, box("avc1", videoEntry + box("avcC", byteArrayOf(1, 100, 0, 31)))) +
                track(2, 48_000, box("mp4a", ByteArray(28) + box("esds", ByteArray(4) + byteArrayOf(5, 2, 0x12, 0x10)))),
        )
        fun traf(id: Int, samples: Int, duration: Long, start: Long): ByteArray = box(
            "traf", box("tfhd", ByteArray(4) + u32(id.toLong())) +
                box("tfdt", ByteArray(4) + u32(start)) +
                box("trun", byteArrayOf(0, 0, 1, 0) + u32(samples.toLong()) +
                    (0 until samples).fold(ByteArray(0)) { bytes, _ -> bytes + u32(duration) }),
        )
        val media = box("moof", box("mfhd", ByteArray(4) + u32(1)) +
            traf(1, 60, 3_000, 90_000) + traf(2, 95, 1_024, 1_024)) + box("mdat", ByteArray(mediaBytes) { 1 })
        return init + media
    }

    private fun box(type: String, payload: ByteArray) = u32((payload.size + 8).toLong()) + type.toByteArray(Charsets.US_ASCII) + payload
    private fun u32(value: Long) = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
}
