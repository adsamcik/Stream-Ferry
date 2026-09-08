package com.adsamcik.streamferry.core.resume

/** File offsets come from MP4 sample tables, never from a file-size/bitrate percentage. */
data class Mp4Chunk(val offset: Long, val length: Long, val start: Long, val end: Long)
data class Mp4Track(
    val timescale: Long,
    val mediaTime: Long,
    val minCompositionOffset: Long,
    val maxCompositionOffset: Long,
    val chunks: List<Mp4Chunk>,
)

/**
 * Bounded parser for conventional, self-contained MP4 audio/video sample tables. Fragmented MP4,
 * external data references, complex edits and unknown layouts deliberately yield no index.
 * HLS fragmented MP4 uses its playlist timeline instead.
 */
object Mp4DeliveryIndex {
    const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_CHUNKS = 100_000

    fun parse(moov: ByteArray): List<Mp4Track>? = runCatching { Reader(moov).parse() }.getOrNull()

    private data class Box(val type: String, val body: Int, val end: Int)
    private class Reader(val data: ByteArray) {
        // Budgets apply to the whole file, not once per track (constant-size sample tables can be tiny).
        private var remainingSamples = MAX_ENTRIES.toLong()
        private var remainingChunks = MAX_CHUNKS
        fun u32(p: Int): Long {
            require(p >= 0 && p <= data.size - 4)
            return (0..3).fold(0L) { value, i -> (value shl 8) or (data[p + i].toLong() and 255) }
        }
        fun u64(p: Int): Long {
            val high = u32(p)
            require(high <= Int.MAX_VALUE)
            return (high shl 32) or u32(p + 4)
        }
        fun text(p: Int) = String(data, p, 4, Charsets.US_ASCII)
        fun children(start: Int, end: Int): List<Box> {
            val boxes = mutableListOf<Box>()
            var p = start
            while (p < end) {
                require(p <= end - 8 && boxes.size < 1024)
                val size = u32(p)
                val header = if (size == 1L) 16 else 8
                val length = if (size == 1L) u64(p + 8) else size
                require(length >= header && length <= end.toLong() - p)
                boxes += Box(text(p + 4), p + header, p + length.toInt())
                p += length.toInt()
            }
            return boxes
        }
        fun Box.children() = children(body, end)
        fun List<Box>.one(type: String) = single { it.type == type }
        fun Box.table(width: Int): Pair<Int, Int> {
            require(end - body >= 8)
            val n = u32(body + 4)
            require(n in 1..MAX_ENTRIES.toLong() && n * width <= end.toLong() - body - 8)
            return body + 8 to n.toInt()
        }
        fun parse(): List<Mp4Track> {
            require(data.size <= MAX_METADATA_BYTES)
            val root = children(0, data.size).one("moov").children()
            require(root.none { it.type == "mvex" })
            val tracks = root.filter { it.type == "trak" }
            require(tracks.size in 1..16)
            return tracks.mapNotNull { track(it) }.also { require(it.isNotEmpty()) }
        }
        fun track(box: Box): Mp4Track? {
            val track = box.children()
            val mdia = track.one("mdia").children()
            val handler = mdia.one("hdlr")
            require(handler.end - handler.body >= 12)
            if (text(handler.body + 8) !in setOf("vide", "soun")) return null
            val mdhd = mdia.one("mdhd")
            require(mdhd.end - mdhd.body >= 24)
            val version = data[mdhd.body].toInt()
            require(version in 0..1)
            val scaleOffset = if (version == 1) 20 else 12
            require(mdhd.end - mdhd.body >= scaleOffset + 8)
            val scale = u32(mdhd.body + scaleOffset)
            require(scale in 1..1_000_000_000)
            var edit = 0L
            track.singleOrNull { it.type == "edts" }?.let { edts ->
                val elst = edts.children().one("elst")
                require(elst.end - elst.body >= 20 && u32(elst.body + 4) == 1L)
                val ev = data[elst.body].toInt()
                require(ev in 0..1)
                val wide = ev == 1
                require(elst.end - elst.body >= if (wide) 28 else 20)
                edit = if (wide) u64(elst.body + 16) else u32(elst.body + 12).also { require(it <= Int.MAX_VALUE) }
                require(u32(elst.body + if (wide) 24 else 16) == 0x00010000L)
            }
            val minf = mdia.one("minf").children()
            val dref = minf.one("dinf").children().one("dref")
            require(dref.end - dref.body >= 8)
            val refs = children(dref.body + 8, dref.end)
            require(refs.size == 1 && refs.single().type == "url ")
            require(refs.single().end - refs.single().body >= 4 && u32(refs.single().body) == 1L)
            val tables = minf.one("stbl").children()
            // Every sample description must refer to the single self-contained data reference.
            val stsd = tables.one("stsd")
            require(stsd.end - stsd.body >= 8)
            val descriptions = children(stsd.body + 8, stsd.end)
            require(descriptions.isNotEmpty() && descriptions.size.toLong() == u32(stsd.body + 4))
            descriptions.forEach {
                require(it.end - it.body >= 8 && (u32(it.body + 4) and 65535) == 1L)
            }
            val stsz = tables.one("stsz")
            require(stsz.end - stsz.body >= 12)
            val fixed = u32(stsz.body + 4)
            val samples = u32(stsz.body + 8)
            require(samples in 1..remainingSamples)
            remainingSamples -= samples
            require(fixed != 0L || samples * 4 <= stsz.end.toLong() - stsz.body - 12)
            val stts = tables.one("stts")
            val (times, timeCount) = stts.table(8)
            var timeSamples = 0L
            repeat(timeCount) { i ->
                require(u32(times + i * 8) > 0 && u32(times + i * 8 + 4) > 0)
                timeSamples += u32(times + i * 8)
            }
            require(timeSamples == samples)
            var minOffset = 0L
            var maxOffset = 0L
            tables.singleOrNull { it.type == "ctts" }?.let { ctts ->
                val (entries, count) = ctts.table(8)
                val cv = data[ctts.body].toInt()
                require(cv in 0..1)
                var total = 0L
                minOffset = Long.MAX_VALUE
                maxOffset = Long.MIN_VALUE
                repeat(count) { i ->
                    val n = u32(entries + i * 8)
                    require(n > 0)
                    total += n
                    val raw = u32(entries + i * 8 + 4)
                    val offset = if (cv == 1) raw.toInt().toLong() else raw
                    minOffset = minOf(minOffset, offset)
                    maxOffset = maxOf(maxOffset, offset)
                }
                require(total == samples && maxOffset - minOffset <= scale * 60)
            }
            val stsc = tables.one("stsc")
            val (mapping, mappingCount) = stsc.table(12)
            val offsets = tables.single { it.type == "stco" || it.type == "co64" }
            val width = if (offsets.type == "co64") 8 else 4
            val (chunks, chunkCount) = offsets.table(width)
            require(chunkCount <= remainingChunks && u32(mapping) == 1L)
            remainingChunks -= chunkCount
            var previousFirst = 0L
            repeat(mappingCount) { i ->
                val first = u32(mapping + i * 12)
                require(first > previousFirst && first <= chunkCount)
                require(u32(mapping + i * 12 + 4) in 1..samples)
                require(u32(mapping + i * 12 + 8) in 1..descriptions.size.toLong())
                previousFirst = first
            }
            var sample = 0
            var mapIndex = 0
            var timeIndex = 0
            var timeLeft = u32(times)
            var decodeTime = 0L
            var previousEnd = -1L
            val result = ArrayList<Mp4Chunk>(chunkCount)
            repeat(chunkCount) { index ->
                if (mapIndex + 1 < mappingCount && u32(mapping + (mapIndex + 1) * 12) == index + 1L) mapIndex++
                val count = u32(mapping + mapIndex * 12 + 4).toInt()
                require(sample.toLong() + count <= samples)
                val offset = if (width == 8) u64(chunks + index * width) else u32(chunks + index * width)
                var bytes = 0L
                val start = decodeTime
                repeat(count) {
                    bytes += if (fixed > 0) fixed else u32(stsz.body + 12 + sample * 4)
                    decodeTime += u32(times + timeIndex * 8 + 4)
                    sample++
                    timeLeft--
                    if (timeLeft == 0L && sample.toLong() < samples) {
                        timeIndex++
                        require(timeIndex < timeCount)
                        timeLeft = u32(times + timeIndex * 8)
                    }
                }
                require(bytes > 0 && offset >= previousEnd && offset <= Long.MAX_VALUE - bytes)
                require(decodeTime <= scale * 7 * 24 * 3600)
                previousEnd = offset + bytes
                result += Mp4Chunk(offset, bytes, start, decodeTime)
            }
            require(sample.toLong() == samples && edit < decodeTime)
            return Mp4Track(scale, edit, minOffset, maxOffset, result)
        }
    }
}
