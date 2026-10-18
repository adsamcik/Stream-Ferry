package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.transcode.StreamingTsMuxer
import com.adsamcik.streamferry.core.transcode.VideoCodec
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingTsMuxerTest {
    @Test
    fun `first encoded sample is delivered with program tables before the encoder closes`() {
        val packets = mutableListOf<ByteArray>()
        val muxer = StreamingTsMuxer(VideoCodec.H264, emptyList(), null, collectPackets(packets))
        muxer.writeVideo(byteArrayOf(0, 0, 0, 1, 0x65, 1), 0, true)

        assertTrue(packets.size >= 4)
        assertTrue(packets.all { it.size == 188 && it[0] == 0x47.toByte() })
        // A program-one PAT with PMT PID 0x1000, including the independent ffmpeg reference CRC.
        assertContentEquals(hex("0000b00d0001c100000001f0002ab104b2"), payload(packets.first()))
        val pmt = payload(packets.single { pid(it) == StreamingTsMuxer.PMT_PID })
        assertEquals(0x1b, pmt[13].unsigned())
        assertEquals(StreamingTsMuxer.VIDEO_PID, ((pmt[14].unsigned() and 31) shl 8) or pmt[15].unsigned())
        assertEquals(22, pmt.size) // pointer + section including one video track and CRC
        assertEquals(0, StreamingTsMuxer.crc32(pmt.copyOfRange(1, pmt.size)))
        assertEquals(90_000L, pts(pes(packets, StreamingTsMuxer.VIDEO_PID)))
    }

    @Test
    fun `HEVC and AAC are signalled and audio gets ADTS with its original access unit and priming time`() {
        val packets = mutableListOf<ByteArray>()
        val muxer = StreamingTsMuxer(VideoCodec.HEVC, listOf(hex("000000014001aa")),
            StreamingTsMuxer.AacConfig(48_000, 2), collectPackets(packets))
        muxer.writeAudio(byteArrayOf(11, 22, 33), -21_333)
        muxer.writeVideo(hex("000000012601ab"), 0, true)

        val pmt = payload(packets.first { pid(it) == StreamingTsMuxer.PMT_PID })
        assertEquals(0x24, pmt[13].unsigned())
        assertEquals(0x0f, pmt[18].unsigned())
        val audio = pes(packets, StreamingTsMuxer.AUDIO_PID)
        assertEquals(88_081L, pts(audio))
        assertContentEquals(hex("fff14c80015ffc0b1621"), audio.copyOfRange(14, audio.size))
        val video = pes(packets, StreamingTsMuxer.VIDEO_PID)
        assertContentEquals(hex("00000001460150000000014001aa000000012601ab"), video.copyOfRange(14, video.size))
    }

    @Test
    fun `transport packet stuffing preserves every access-unit byte and continuity through wraparound`() {
        val packets = mutableListOf<ByteArray>()
        val sample = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(10_017) { (it % 251).toByte() }
        StreamingTsMuxer(VideoCodec.H264, emptyList(), null, collectPackets(packets)).writeVideo(sample, 123_456, true)
        val videoPackets = packets.filter { pid(it) == StreamingTsMuxer.VIDEO_PID && hasPayload(it) }
        assertTrue(videoPackets.size > 32)
        videoPackets.forEachIndexed { index, packet ->
            assertEquals(index and 15, packet[3].unsigned() and 15)
            assertEquals(index == 0, packet[1].unsigned() and 0x40 != 0)
        }
        assertEquals(0x40, videoPackets.first()[5].unsigned() and 0x40)
        val elementary = pes(packets, StreamingTsMuxer.VIDEO_PID).drop(14).toByteArray()
        assertContentEquals(hex("0000000109f0") + sample, elementary)
    }

    @Test
    fun `one-byte transport padding uses a zero-length adaptation field without consuming PES bytes`() {
        val packets = mutableListOf<ByteArray>()
        val sample = hex("0000000141") + ByteArray(158) { 7 }
        StreamingTsMuxer(VideoCodec.H264, emptyList(), null, collectPackets(packets)).writeVideo(sample, 0, false)
        val packet = packets.single { pid(it) == StreamingTsMuxer.VIDEO_PID && hasPayload(it) }
        assertEquals(0, packet[4].unsigned())
        assertEquals(183, payload(packet).size)
        assertContentEquals(hex("0000000109f0") + sample, payload(packet).drop(14).toByteArray())
    }

    @Test
    fun `AVC configuration and length-prefixed samples become Annex B at each keyframe`() {
        val packets = mutableListOf<ByteArray>()
        val avcc = hex("0142001effe10002674201000268ce")
        val muxer = StreamingTsMuxer(VideoCodec.H264, listOf(avcc), null, collectPackets(packets))
        muxer.writeVideo(hex("0000000265aa0000000206bb"), 0, true)
        assertContentEquals(hex("0000000109f00000000167420000000168ce0000000165aa0000000106bb"),
            pes(packets, StreamingTsMuxer.VIDEO_PID).drop(14).toByteArray())
    }

    @Test
    fun `HEVC configuration arrays are repeated before the first access unit`() {
        val packets = mutableListOf<ByteArray>()
        val hvcc = ByteArray(23).apply { this[0] = 1; this[21] = 3; this[22] = 1 } + hex("a0000100034001aa")
        StreamingTsMuxer(VideoCodec.HEVC, listOf(hvcc), null, collectPackets(packets))
            .writeVideo(hex("000000032601bb"), 0, true)
        assertContentEquals(hex("00000001460150000000014001aa000000012601bb"),
            pes(packets, StreamingTsMuxer.VIDEO_PID).drop(14).toByteArray())
    }

    @Test
    fun `AAC keeps the PCR alive for low-frame-rate video and repeats the program tables`() {
        val packets = mutableListOf<ByteArray>()
        val muxer = StreamingTsMuxer(VideoCodec.H264, emptyList(), StreamingTsMuxer.AacConfig(44_100, 1), collectPackets(packets))
        muxer.writeVideo(hex("00000165ff"), 0, true)
        for (time in 20_000L..240_000L step 20_000) muxer.writeAudio(byteArrayOf(1), time)
        val clocks = packets.filter { pid(it) == StreamingTsMuxer.VIDEO_PID && !hasPayload(it) }
        val bases = clocks.map {
            (it[6].unsigned().toLong() shl 25) or (it[7].unsigned().toLong() shl 17) or
                (it[8].unsigned().toLong() shl 9) or (it[9].unsigned().toLong() shl 1) or (it[10].unsigned().toLong() ushr 7)
        }
        assertEquals(listOf(45_000L, 48_600L, 52_200L, 55_800L, 59_400L, 63_000L, 66_600L), bases)
        assertEquals(3, packets.count { pid(it) == 0 })
        assertTrue(clocks.all { it[3].unsigned() and 15 == 0 })
    }

    @Test
    fun `AAC configuration preserves the encoded core rate for LC and HE-AAC`() {
        assertEquals(StreamingTsMuxer.AacConfig(44_100, 2),
            StreamingTsMuxer.AacConfig.fromAudioSpecificConfig(hex("1210")))
        assertEquals(StreamingTsMuxer.AacConfig(22_050, 2),
            StreamingTsMuxer.AacConfig.fromAudioSpecificConfig(hex("2b920800")))
        assertFailsWith<IllegalArgumentException> { StreamingTsMuxer.AacConfig.fromAudioSpecificConfig(byteArrayOf(0)) }
    }

    @Test
    fun `invalid or reordered encoder output fails rather than silently producing corrupt media`() {
        val muxer = StreamingTsMuxer(VideoCodec.H264, emptyList(), null) {}
        muxer.writeVideo(hex("00000165ff"), 10_000, true)
        assertFailsWith<IllegalArgumentException> { muxer.writeVideo(hex("00000141ff"), 0, false) }
        assertFailsWith<IllegalArgumentException> { muxer.writeVideo(hex("000000ff01"), 20_000, false) }
        assertFailsWith<IllegalStateException> { muxer.writeAudio(byteArrayOf(1), 0) }
        muxer.close()
        assertFailsWith<IllegalStateException> { muxer.writeVideo(hex("00000141ff"), 20_000, false) }
    }

    @Test
    fun `bounded chunks preserve the reference packet stream for AVC and HEVC with AAC`() {
        // Digests captured from the original per-packet writer, before batching was introduced.
        val references = mapOf(
            VideoCodec.H264 to "8db1ceb3e98d20574d9fd809b30acf1a6acacdea13e9f6dfdeb5002d0aa4bfcb",
            VideoCodec.HEVC to "7a1f05361a1243a523ac5a379293c571e3f306d744a3c3f29405df3ca23972c6",
        )
        val sample = ByteArray(32_000) { (it % 251).toByte() }.apply {
            this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1; this[4] = 0x65
        }
        val audio = ByteArray(256) { (it % 127).toByte() }
        for ((codec, reference) in references) {
            val digest = MessageDigest.getInstance("SHA-256")
            var chunks = 0
            var packets = 0
            val muxer = StreamingTsMuxer(codec, listOf(hex("00000001670102")),
                StreamingTsMuxer.AacConfig(48_000, 2)) { chunk ->
                assertTrue(chunk.size in 188..6_016)
                assertEquals(0, chunk.size % 188)
                for (offset in chunk.indices step 188) assertEquals(0x47.toByte(), chunk[offset])
                chunks++
                packets += chunk.size / 188
                digest.update(chunk)
            }
            repeat(100) { index ->
                muxer.writeVideo(sample, index * 33_333L, index % 30 == 0)
                muxer.writeAudio(audio, index * 33_333L)
            }
            assertTrue(chunks * 10 < packets, "packet queues should receive batches")
            assertEquals(reference, digest.digest().joinToString("") { "%02x".format(it) })
            val delivered = chunks
            muxer.close()
            assertEquals(delivered, chunks, "no output should wait for close")
        }
    }

    @Test
    fun `sample writes wait for the consumer and stop immediately when it fails`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var chunks = 0
        val failure = IllegalStateException("receiver disconnected")
        val muxer = StreamingTsMuxer(VideoCodec.H264, emptyList(), null) { chunk ->
            chunks++
            if (chunk.size > 188) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                throw failure
            }
        }
        try {
            val result = executor.submit<Throwable?> {
                runCatching { muxer.writeVideo(hex("0000000165") + ByteArray(100_000), 0, true) }.exceptionOrNull()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(result.isDone, "the writer must respect synchronous backpressure")
            resume.countDown()
            assertEquals(failure, result.get(5, TimeUnit.SECONDS))
            assertEquals(4, chunks, "no later media chunk may be queued after the consumer fails")
            muxer.close()
            assertEquals(4, chunks)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private fun collectPackets(packets: MutableList<ByteArray>): (ByteArray) -> Unit = { chunk ->
        for (offset in chunk.indices step 188) packets += chunk.copyOfRange(offset, offset + 188)
    }

    private fun pid(packet: ByteArray) = ((packet[1].unsigned() and 31) shl 8) or packet[2].unsigned()
    private fun hasPayload(packet: ByteArray) = packet[3].unsigned() and 0x10 != 0
    private fun payload(packet: ByteArray): ByteArray {
        val start = if (packet[3].unsigned() and 0x20 != 0) 5 + packet[4].unsigned() else 4
        return packet.copyOfRange(start, packet.size)
    }
    private fun pes(packets: List<ByteArray>, pid: Int) = packets.filter { pid(it) == pid && hasPayload(it) }
        .flatMap { payload(it).asList() }.toByteArray()
    private fun pts(pes: ByteArray): Long = ((pes[9].unsigned().toLong() ushr 1 and 7) shl 30) or
        (pes[10].unsigned().toLong() shl 22) or ((pes[11].unsigned().toLong() ushr 1) shl 15) or
        (pes[12].unsigned().toLong() shl 7) or (pes[13].unsigned().toLong() ushr 1)
    private fun Byte.unsigned() = toInt() and 0xff
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
