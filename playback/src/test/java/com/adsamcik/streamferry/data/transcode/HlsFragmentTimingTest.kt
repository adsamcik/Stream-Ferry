package com.adsamcik.streamferry.data.transcode

import java.nio.ByteBuffer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure container/timestamp regressions; actual platform muxer coverage lives alongside this test. */
class HlsFragmentTimingTest {
    @Test fun `fragment waits for each tracks next timestamp and preserves payload`() {
        val published = mutableListOf<ByteArray>()
        val normalizer = HlsFragmentNormalizer(published::add)
        normalizer.setInitialization(initialization())
        normalizer.sample(1, 20_000)
        normalizer.sample(1, 50_000)
        normalizer.sample(2, 0)
        normalizer.sample(2, 21_333)
        val (moof, mdat) = fragment()
        normalizer.submit(moof, mdat)
        assertTrue(published.isEmpty(), "neither approximate fragment tail may be published")
        normalizer.sample(1, 90_000)
        assertTrue(published.isEmpty(), "audio still needs its own lookahead")
        normalizer.sample(2, 42_666)
        val media = published.single()
        val moofSize = ByteBuffer.wrap(media).int
        assertContentEquals(mdat, media.copyOfRange(moofSize, media.size))
        assertEquals(listOf(20L, 0L), fields(media, "tfdt").map { ByteBuffer.wrap(media).getLong(it + 12) })
        val runs = fields(media, "trun")
        assertEquals(listOf(30, 40), (0..1).map { ByteBuffer.wrap(media).getInt(runs[0] + 20 + it * 12) })
        assertEquals(listOf(21, 21), (0..1).map { ByteBuffer.wrap(media).getInt(runs[1] + 20 + it * 12) })
    }

    @Test fun `explicit EOS supplies final singleton duration and finish publishes it once`() {
        val published = mutableListOf<ByteArray>()
        val normalizer = HlsFragmentNormalizer(published::add)
        normalizer.setInitialization(initialization())
        normalizer.sample(1, 20_000)
        normalizer.sample(2, 0)
        val (moof, mdat) = fragment(count = 1)
        normalizer.submit(moof, mdat)
        normalizer.sample(1, 65_000, endOfStream = true)
        assertTrue(published.isEmpty())
        normalizer.sample(2, 21_333, endOfStream = true)
        normalizer.finish()
        assertEquals(1, published.size)
        val media = published.single()
        assertEquals(listOf(45, 21), fields(media, "trun").map { ByteBuffer.wrap(media).getInt(it + 20) })
        assertFailsWith<IllegalStateException> { normalizer.finish() }
    }

    @Test fun `missing lookahead remains bounded and missing timestamps fail closed`() {
        val normalizer = HlsFragmentNormalizer { error("must not publish") }
        normalizer.setInitialization(initialization())
        val (moof, _) = fragment()
        assertFailsWith<IllegalArgumentException> { normalizer.submit(moof, ByteArray(24 * 1024 * 1024)) }
        val other = HlsFragmentNormalizer { error("must not publish") }
        other.setInitialization(initialization())
        val (header, data) = fragment()
        other.submit(header, data)
        assertFailsWith<IllegalStateException> { other.finish() }
    }

    private fun initialization(): ByteArray = box("moov", (1..2).map { id ->
        val tkhd = box("tkhd", ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(0).putInt(id).array())
        val mdhd = box("mdhd", ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(0).putInt(1000).array())
        box("trak", tkhd + box("mdia", mdhd))
    }.reduce(ByteArray::plus))

    private fun fragment(count: Int = 2): Pair<ByteArray, ByteArray> {
        fun track(id: Int, offset: Int): ByteArray {
            val tfhd = box("tfhd", ByteBuffer.allocate(16).putInt(1).putInt(id).putLong(0).array())
            val trun = ByteBuffer.allocate(12 + count * 12).putInt(0x701).putInt(count).putInt(offset)
            repeat(count) { trun.putInt(999).putInt(1).putInt(0) }
            return box("traf", tfhd + box("trun", trun.array()))
        }
        val preliminary = box("moof", track(1, 0) + track(2, 0))
        return box("moof", track(1, preliminary.size + 8) + track(2, preliminary.size + 8 + count)) to
            box("mdat", ByteArray(count * 2) { (it + 1).toByte() })
    }

    private fun fields(bytes: ByteArray, type: String): List<Int> = (0 until bytes.size - 7).filter {
        String(bytes, it + 4, 4, Charsets.US_ASCII) == type
    }
    private fun box(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
}
