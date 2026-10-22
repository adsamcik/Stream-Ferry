package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Converts pinned Media3's file-relative fragments into independently addressable HLS fragments. */
internal class HlsFragmentNormalizer(private val publish: (ByteArray) -> Unit) {
    private var timescales: Map<Int, Long> = emptyMap()
    private val timestamps = mutableMapOf<Int, ArrayDeque<Long>>()
    private val ended = mutableMapOf<Int, Long>()
    private data class Pending(val moof: ByteArray, val mdat: ByteArray)
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0L
    private var finished = false
    private val lastSampleDuration = mutableMapOf<Int, Long>()

    /** Called before forwarding each rebased encoded sample to Media3, including EOS. No B frames. */
    fun sample(initTrackId: Int, ptsUs: Long, endOfStream: Boolean = false) {
        require(ptsUs >= 0)
        if (endOfStream) ended[initTrackId] = ptsUs else {
            val queue = timestamps.getOrPut(initTrackId) { ArrayDeque() }
            require(queue.size < 100_000 && (queue.peekLast()?.let { ptsUs > it } != false)) {
                "Streaming output requires bounded, monotonically increasing encoded timestamps"
            }
            queue.addLast(ptsUs)
        }
        drain()
    }

    fun setInitialization(bytes: ByteArray) {
        timescales = Fmp4Splitter.trackTimescales(bytes)
        require(timescales.isNotEmpty()) { "Streaming init has no track clocks" }
    }

    fun submit(moof: ByteArray, mdat: ByteArray) {
        require(!finished)
        require(pendingBytes + moof.size + mdat.size <= 24 * 1024 * 1024L) {
            "Encoded tracks did not provide bounded fragment timing lookahead"
        }
        pending.addLast(Pending(moof, mdat))
        pendingBytes += moof.size + mdat.size
        drain()
    }

    /** The only durations that may need estimation are the actual end-of-stream tails. */
    fun finish() {
        check(!finished)
        finished = true
        drain()
        check(pending.isEmpty()) { "Streaming fragment timestamps are incomplete" }
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            val item = pending.first()
            val trafs = boxes(item.moof, 8, item.moof.size).filter { it.type == "traf" }
            if (trafs.any { traf ->
                    val children = boxes(item.moof, traf.start + 8, traf.end)
                    val id = int(item.moof, children.single { it.type == "tfhd" }.start + 12)
                    val count = children.filter { it.type == "trun" }.sumOf { int(item.moof, it.start + 12) }
                    val available = timestamps[id]?.size ?: 0
                    available < count || (available == count && id !in ended && !finished)
                }) return
            pending.removeFirst()
            pendingBytes -= item.moof.size + item.mdat.size
            publish(normalize(item.moof, item.mdat))
        }
    }

    private fun normalize(bytes: ByteArray, mdat: ByteArray): ByteArray {
        val moof = boxes(bytes, 0, bytes.size).single().also { require(it.type == "moof") }
        require(boxes(mdat, 0, mdat.size).single().type == "mdat")
        val children = boxes(bytes, moof.start + 8, moof.end)
        val trafCount = children.count { it.type == "traf" }
        require(trafCount > 0)
        // Every pinned tfhd loses its eight-byte absolute offset and gains a twenty-byte tfdt.
        val sizeDelta = trafCount * 12
        val normalizedChildren = children.map { child ->
            if (child.type != "traf") bytes.copyOfRange(child.start, child.end)
            else normalizeTrack(bytes, child, sizeDelta, moof.end, mdat.size)
        }
        // Only the small header is rebuilt; the payload has one final destination allocation.
        val result = ByteBuffer.allocate(bytes.size + sizeDelta + mdat.size)
            .putInt(bytes.size + sizeDelta).put("moof".toByteArray(Charsets.US_ASCII))
        normalizedChildren.forEach { result.put(it) }
        return result.put(mdat).array()
    }

    private fun normalizeTrack(bytes: ByteArray, traf: Box, sizeDelta: Int, mdatStart: Int, mdatSize: Int): ByteArray {
        val children = boxes(bytes, traf.start + 8, traf.end)
        val tfhd = children.single { it.type == "tfhd" }
        require(tfhd.end - tfhd.start == 24 && int(bytes, tfhd.start + 8) == 1) {
            "Unexpected Media3 fragment header layout"
        }
        require(children.none { it.type == "tfdt" }) { "Unexpected pre-timed Media3 fragment" }
        val trackId = int(bytes, tfhd.start + 12)
        val scale = timescales[trackId] ?: error("Fragment track is absent from its init")
        val queue = timestamps.getValue(trackId)
        val start = ticks(queue.first(), scale)
        var duration = 0L
        val timing = box("tfdt", ByteBuffer.allocate(12).putInt(0x01000000).putLong(start).array())
        val normalized = children.map { child ->
            when (child.type) {
                "tfhd" -> box("tfhd", ByteBuffer.allocate(8).putInt(0x020000).putInt(trackId).array()) + timing
                "trun" -> bytes.copyOfRange(child.start, child.end).also { trun ->
                    val flags = int(trun, 8) and 0xffffff
                    require(flags and 0x701 == 0x701 && flags and 4 == 0) { "Unexpected Media3 sample layout" }
                    val count = int(trun, 12)
                    require(count in 1..1_000_000)
                    val stride = 12 + if (flags and 0x800 != 0) 4 else 0
                    require(20L + count.toLong() * stride == trun.size.toLong())
                    val offset = int(trun, 16)
                    var mediaBytes = 0L
                    repeat(count) { sample ->
                        val sampleOffset = 20 + sample * stride
                        val pts = queue.removeFirst()
                        val next = queue.peekFirst() ?: ended[trackId]?.takeIf { it > pts }
                        val sampleDuration = if (next != null) ticks(next, scale) - ticks(pts, scale)
                            else lastSampleDuration[trackId] ?: uint(trun, sampleOffset)
                        require(sampleDuration in 1..Int.MAX_VALUE.toLong() && duration <= Long.MAX_VALUE - sampleDuration)
                        ByteBuffer.wrap(trun).putInt(sampleOffset, sampleDuration.toInt())
                        lastSampleDuration[trackId] = sampleDuration
                        duration += sampleDuration
                        mediaBytes += uint(trun, sampleOffset + 4)
                    }
                    require(offset >= mdatStart + 8 && offset.toLong() + mediaBytes <= mdatStart.toLong() + mdatSize) {
                        "Streaming sample offsets escape their media data"
                    }
                    ByteBuffer.wrap(trun).putInt(16, Math.addExact(offset, sizeDelta))
                }
                else -> bytes.copyOfRange(child.start, child.end)
            }
        }
        require(duration > 0 && start <= Long.MAX_VALUE - duration)
        return box("traf", normalized.reduce(ByteArray::plus))
    }

    private fun ticks(us: Long, scale: Long): Long =
        Math.addExact(Math.multiplyExact(us / 1_000_000, scale), (us % 1_000_000) * scale / 1_000_000)

    private data class Box(val type: String, val start: Int, val end: Int)
    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> = buildList {
        var cursor = from
        while (cursor < to) {
            require(cursor <= to - 8)
            val length = uint(bytes, cursor)
            require(length in 8..(to - cursor).toLong())
            add(Box(String(bytes, cursor + 4, 4, Charsets.US_ASCII), cursor, cursor + length.toInt()))
            cursor += length.toInt()
        }
    }

    private fun int(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).getInt(offset)
    private fun uint(bytes: ByteArray, offset: Int) = int(bytes, offset).toLong() and 0xffffffffL
    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
}
