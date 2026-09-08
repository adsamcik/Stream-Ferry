package com.adsamcik.streamferry.core.resume

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class Mp4DeliveryTrackerTest {
    @Test fun `real muxed H264 AAC file maps delivered samples to its six second timeline`() {
        val file = requireNotNull(javaClass.getResourceAsStream("/progress/six-seconds-av.mp4")).use { it.readBytes() }
        val tracker = Mp4DeliveryTracker()
        var spans = emptyList<DeliveredMediaSpan>()
        for (offset in file.indices step 37) {
            val part = file.copyOfRange(offset, minOf(offset + 37, file.size))
            spans = tracker.written(offset.toLong(), part, part.size).ifEmpty { spans }
        }
        assertEquals(1, spans.size)
        assertTrue(spans.single().startMs in 0..200)
        assertTrue(spans.single().endMs in 5800..6000, spans.toString())
    }

    private fun words(vararg values: Long): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream -> values.forEach { stream.writeInt(it.toInt()) } }
    }.toByteArray()
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(byteArrayOf()) { a, b -> a + b }
        return words(body.size + 8L) + type.toByteArray() + body
    }
    private val prefix = box("ftyp", "isom".toByteArray(), words(0), "isom".toByteArray())
    private val start = prefix.size + 8L
    private fun track(kind: String, offsets: LongArray, sizes: LongArray, composition: Boolean = false, edit: Long = 0): ByteArray =
        box("trak",
            box("edts", box("elst", words(0, 1, 20_000, edit, 0x10000))),
            box("mdia",
                box("mdhd", words(0, 0, 0, 1000, 20_000, 0)),
                box("hdlr", words(0, 0), kind.toByteArray()),
                box("minf",
                    box("dinf", box("dref", words(0, 1), box("url ", words(1)))),
                    box("stbl",
                        box("stsd", words(0, 1), box(if (kind == "vide") "avc1" else "mp4a", words(0, 1))),
                        box("stts", words(0, 1, 4, 5000)),
                        if (composition) box("ctts", words(0, 2, 2, 2000, 2, 0)) else byteArrayOf(),
                        box("stsc", words(0, 1, 1, 2, 1)),
                        box("stsz", words(0, 0, sizes.size.toLong(), *sizes)),
                        box("stco", words(0, offsets.size.toLong(), *offsets)),
                    ),
                ),
            ),
        )
    private fun moov(composition: Boolean = false, edit: Long = 0) = box("moov",
        track("vide", longArrayOf(start, start + 20), longArrayOf(2, 10, 20, 8), composition, edit),
        track("soun", longArrayOf(start + 12, start + 48), longArrayOf(3, 5, 14, 18)),
    )
    private fun file(composition: Boolean = false) = prefix + box("mdat", ByteArray(80)) + moov(composition)
    private fun index(tracker: Mp4DeliveryTracker, file: ByteArray) {
        assertTrue(tracker.written(0, file.copyOf(start.toInt()), start.toInt()).isEmpty())
        val tail = file.copyOfRange(start.toInt() + 80, file.size)
        assertTrue(tracker.written(start + 80, tail, tail.size).isEmpty())
    }

    @Test fun `variable sized interleaved chunks require both audio and video delivery`() {
        val tracker = Mp4DeliveryTracker()
        index(tracker, file())
        assertTrue(tracker.written(start, ByteArray(12), 12).isEmpty())
        assertEquals(listOf(DeliveredMediaSpan(0, 10_000)), tracker.written(start + 12, ByteArray(8), 8))
        assertTrue(tracker.written(start + 48, ByteArray(32), 32).all { it.endMs <= 10_000 })
        assertEquals(listOf(DeliveredMediaSpan(0, 20_000)), tracker.written(start + 20, ByteArray(28), 28))
        assertTrue(tracker.written(start, ByteArray(80), 80).isEmpty(), "Retries must not refresh evidence")
    }

    @Test fun `partial sample cannot count and seek clears delivered coverage but keeps the index`() {
        val tracker = Mp4DeliveryTracker()
        index(tracker, file())
        assertTrue(tracker.written(start, ByteArray(19), 19).isEmpty())
        assertEquals(listOf(DeliveredMediaSpan(0, 10_000)), tracker.written(start + 19, byteArrayOf(0), 1))
        tracker.resetDelivery()
        assertTrue(tracker.written(start + 20, ByteArray(28), 28).isEmpty())
        assertEquals(listOf(DeliveredMediaSpan(10_000, 20_000)), tracker.written(start + 48, ByteArray(32), 32))
    }

    @Test fun `split metadata reads and B frame offsets shrink only the outer run edges`() {
        val tracker = Mp4DeliveryTracker()
        val file = file(composition = true)
        var spans = emptyList<DeliveredMediaSpan>()
        file.indices.forEach { i -> spans = tracker.written(i.toLong(), byteArrayOf(file[i]), 1).ifEmpty { spans } }
        assertEquals(listOf(DeliveredMediaSpan(2000, 20_000)), spans)
    }

    @Test fun `simple edit trim is supported but fragmented and malformed indexes fall back`() {
        assertEquals(2000, Mp4DeliveryIndex.parse(moov(edit = 2000))!!.first().mediaTime)
        assertNull(Mp4DeliveryIndex.parse(box("moov", box("mvex"))))
        val valid = moov()
        for (cut in valid.indices step 13) assertNull(Mp4DeliveryIndex.parse(valid.copyOf(cut)))
        assertNull(Mp4DeliveryIndex.parse(moov(edit = 0xffffffffL)))
        val tracker = Mp4DeliveryTracker()
        val excessive = words(Mp4DeliveryIndex.MAX_METADATA_BYTES + 1L) + "moov".toByteArray()
        assertTrue(tracker.written(0, excessive, excessive.size).isEmpty())
        assertTrue(tracker.written(8, ByteArray(1000), 1000).isEmpty())
    }
}
