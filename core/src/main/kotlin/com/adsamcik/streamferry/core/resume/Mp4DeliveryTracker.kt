package com.adsamcik.streamferry.core.resume

import java.util.TreeMap

data class DeliveredMediaSpan(val startMs: Long, val endMs: Long)

/**
 * Observes only bytes already written to the renderer. Reads a bounded moov atom opportunistically;
 * performs no I/O and retains no media payload. Top-level atom sizes let tail Range requests find
 * a moov after mdat without scanning compressed data for magic strings.
 */
class Mp4DeliveryTracker {
    private var nextHeader = 0L
    private val header = ByteArray(16)
    private var headerCount = 0
    private var metadata: ByteArray? = null
    private var metadataCount = 0
    private var metadataStart = 0L
    private var metadataDone = false
    private var tracks: List<Mp4Track>? = null
    private val bytes = TreeMap<Long, Long>()
    private var runs: List<TreeMap<Int, Int>> = emptyList()

    /** A seek keeps the file index but discards all delivery evidence from the previous epoch. */
    @Synchronized fun resetDelivery() {
        bytes.clear()
        runs.forEach { it.clear() }
    }

    @Synchronized fun written(offset: Long, buffer: ByteArray, length: Int): List<DeliveredMediaSpan> {
        if (offset < 0 || length !in 1..buffer.size || offset > Long.MAX_VALUE - length) return emptyList()
        val end = offset + length
        val window = mergeBytes(offset, end)
        val hadIndex = tracks != null
        if (!metadataDone) readMetadata(offset, buffer, length)
        val index = tracks ?: return emptyList()
        var changed = false
        val windows = if (hadIndex) listOf(window) else bytes.map { it.key to it.value }
        for ((start, finish) in windows) {
            index.forEachIndexed { trackIndex, track ->
                val chunks = track.chunks
                var low = 0
                var high = chunks.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (chunks[mid].offset < start) low = mid + 1 else high = mid
                }
                while (low < chunks.size && chunks[low].offset + chunks[low].length <= finish) {
                    val existing = runs[trackIndex].floorEntry(low)
                    if (existing != null && existing.value >= low) {
                        low = existing.value + 1
                        continue
                    }
                    mergeRun(runs[trackIndex], low)
                    changed = true
                    low++
                }
            }
        }
        if (!changed) return emptyList()
        // Audio/video tracks can be interleaved unevenly. Only their common delivered timeline counts.
        return index.indices.map { i -> timeline(index[i], runs[i]) }
            .reduce { a, b -> intersect(a, b) }
    }

    private fun mergeBytes(begin: Long, finish: Long): Pair<Long, Long> {
        var start = begin
        var end = finish
        bytes.floorEntry(start)?.takeIf { it.value >= start }?.let {
            start = it.key; end = maxOf(end, it.value); bytes.remove(it.key)
        }
        var next = bytes.ceilingEntry(start)
        while (next != null && next.key <= end) {
            end = maxOf(end, next.value); bytes.remove(next.key); next = bytes.ceilingEntry(start)
        }
        bytes[start] = end
        while (bytes.size > 512) bytes.pollFirstEntry()
        return start to end
    }

    private fun mergeRun(map: TreeMap<Int, Int>, chunk: Int) {
        val previous = map.floorEntry(chunk)?.takeIf { it.value + 1 == chunk }
        val next = map.ceilingEntry(chunk)?.takeIf { it.key == chunk + 1 }
        val start = previous?.key ?: chunk
        val end = next?.value ?: chunk
        if (next != null) map.remove(next.key)
        map[start] = end
        while (map.size > 512) map.pollFirstEntry()
    }

    private fun timeline(track: Mp4Track, map: TreeMap<Int, Int>): List<DeliveredMediaSpan> = map.mapNotNull { (first, last) ->
        // Shrink the outer edges of an entire contiguous run to allow for reordered video frames.
        val start = (track.chunks[first].start + track.maxCompositionOffset - track.mediaTime).coerceAtLeast(0)
        val end = (track.chunks[last].end + track.minCompositionOffset - track.mediaTime).coerceAtLeast(0)
        val startMs = (start * 1000 + track.timescale - 1) / track.timescale
        val endMs = end * 1000 / track.timescale
        if (endMs > startMs) DeliveredMediaSpan(startMs, endMs) else null
    }

    private fun intersect(a: List<DeliveredMediaSpan>, b: List<DeliveredMediaSpan>): List<DeliveredMediaSpan> {
        val result = mutableListOf<DeliveredMediaSpan>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].startMs, b[j].startMs)
            val end = minOf(a[i].endMs, b[j].endMs)
            if (end > start) result += DeliveredMediaSpan(start, end)
            if (a[i].endMs <= b[j].endMs) i++ else j++
        }
        return result
    }

    private fun u32(p: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (header[p + i].toLong() and 255) }

    private fun readMetadata(offset: Long, buffer: ByteArray, length: Int) {
        // Each iteration consumes a header or metadata bytes, or jumps to the next top-level atom.
        var steps = 0
        while (!metadataDone && steps++ < 1024) {
            val collecting = metadata
            val expected = if (collecting != null) metadataStart + metadataCount else nextHeader + headerCount
            if (expected < offset || expected >= offset + length) return
            val source = (expected - offset).toInt()
            if (collecting != null) {
                val n = minOf(length - source, collecting.size - metadataCount)
                buffer.copyInto(collecting, metadataCount, source, source + n)
                metadataCount += n
                if (metadataCount == collecting.size) {
                    tracks = Mp4DeliveryIndex.parse(collecting)
                    runs = tracks.orEmpty().map { TreeMap() }
                    metadata = null
                    metadataDone = true
                }
                continue
            }
            val required = if (headerCount >= 8 && u32(0) == 1L) 16 else 8
            val n = minOf(length - source, required - headerCount)
            buffer.copyInto(header, headerCount, source, source + n)
            headerCount += n
            if (headerCount < 8 || (u32(0) == 1L && headerCount < 16)) continue
            val high = if (headerCount == 16) u32(8) else 0
            val size = if (headerCount == 16 && high <= Int.MAX_VALUE) (high shl 32) or u32(12) else u32(0)
            if (high > Int.MAX_VALUE || size < headerCount || size > Long.MAX_VALUE - nextHeader) {
                metadataDone = true
                return
            }
            val type = String(header, 4, 4, Charsets.US_ASCII)
            if (type == "moov") {
                if (size > Mp4DeliveryIndex.MAX_METADATA_BYTES) { metadataDone = true; return }
                metadata = ByteArray(size.toInt()).also { header.copyInto(it, 0, 0, headerCount) }
                metadataCount = headerCount
                metadataStart = nextHeader
                // An empty moov is invalid, but must not leave a zero-length copy loop.
                if (metadataCount == size.toInt()) { metadata = null; metadataDone = true }
            } else {
                nextHeader += size
            }
            headerCount = 0
        }
    }
}
