package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import java.nio.ByteBuffer

/** Converts pinned Media3's file-relative fragments into independently addressable HLS fragments. */
internal class HlsFragmentNormalizer {
    private var timescales: Map<Int, Long> = emptyMap()
    private val firstPtsUs = mutableMapOf<Int, Long>()
    private val nextDecodeTime = mutableMapOf<Int, Long>()
    private val lastSampleDuration = mutableMapOf<Int, Long>()

    fun setTrackStart(initTrackId: Int, ptsUs: Long) {
        require(ptsUs >= 0)
        firstPtsUs.putIfAbsent(initTrackId, ptsUs)
    }

    fun setInitialization(bytes: ByteArray) {
        timescales = Fmp4Splitter.trackTimescales(bytes)
        require(timescales.isNotEmpty()) { "Streaming init has no track clocks" }
    }

    fun normalize(bytes: ByteArray): ByteArray {
        val top = boxes(bytes, 0, bytes.size)
        require(top.size == 2 && top[0].type == "moof" && top[1].type == "mdat")
        val moof = top[0]
        val children = boxes(bytes, moof.start + 8, moof.end)
        val trafCount = children.count { it.type == "traf" }
        require(trafCount > 0)
        // Every pinned tfhd loses its eight-byte absolute offset and gains a twenty-byte tfdt.
        val sizeDelta = trafCount * 12
        val normalizedChildren = children.map { child ->
            if (child.type != "traf") bytes.copyOfRange(child.start, child.end)
            else normalizeTrack(bytes, child, sizeDelta, top[1])
        }
        return box("moof", normalizedChildren.reduce(ByteArray::plus)) + bytes.copyOfRange(top[1].start, top[1].end)
    }

    private fun normalizeTrack(bytes: ByteArray, traf: Box, sizeDelta: Int, mdat: Box): ByteArray {
        val children = boxes(bytes, traf.start + 8, traf.end)
        val tfhd = children.single { it.type == "tfhd" }
        require(tfhd.end - tfhd.start == 24 && int(bytes, tfhd.start + 8) == 1) {
            "Unexpected Media3 fragment header layout"
        }
        require(children.none { it.type == "tfdt" }) { "Unexpected pre-timed Media3 fragment" }
        val trackId = int(bytes, tfhd.start + 12)
        val scale = timescales[trackId] ?: error("Fragment track is absent from its init")
        val start = nextDecodeTime[trackId] ?: run {
            val ptsUs = firstPtsUs[trackId] ?: error("Fragment track has no first encoded timestamp")
            Math.round(ptsUs.toDouble() * scale / 1_000_000)
        }
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
                        val writtenDuration = uint(trun, sampleOffset)
                        // Media3 repeats the preceding duration at EOF, but a fragment containing
                        // only its final frame has no local predecessor. Carry the actual prior
                        // fragment's duration instead of dropping that frame or publishing zero time.
                        val sampleDuration = if (writtenDuration == 0L && count == 1) {
                            lastSampleDuration[trackId] ?: 0L
                        } else writtenDuration
                        require(sampleDuration > 0 && duration <= Long.MAX_VALUE - sampleDuration)
                        if (sampleDuration != writtenDuration) ByteBuffer.wrap(trun).putInt(sampleOffset, sampleDuration.toInt())
                        lastSampleDuration[trackId] = sampleDuration
                        duration += sampleDuration
                        mediaBytes += uint(trun, sampleOffset + 4)
                    }
                    require(offset >= mdat.start + 8 && offset.toLong() + mediaBytes <= mdat.end) {
                        "Streaming sample offsets escape their media data"
                    }
                    ByteBuffer.wrap(trun).putInt(16, Math.addExact(offset, sizeDelta))
                }
                else -> bytes.copyOfRange(child.start, child.end)
            }
        }
        require(duration > 0 && start <= Long.MAX_VALUE - duration)
        nextDecodeTime[trackId] = start + duration
        return box("traf", normalized.reduce(ByteArray::plus))
    }

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
