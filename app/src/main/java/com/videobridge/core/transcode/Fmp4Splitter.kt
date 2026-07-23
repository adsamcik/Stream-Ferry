package com.videobridge.core.transcode

/**
 * Splits a fragmented-MP4 (CMAF) byte stream into an HLS **init** segment (everything before the first
 * `moof`: `ftyp` + `moov` + setup boxes) and a **media** segment (the first `moof` to end).
 *
 * On-device transcoding produces a self-contained fragmented MP4 per HLS segment; serving each whole
 * file would repeat `ftyp`/`moov`. Instead we publish ONE `EXT-X-MAP` init (from the first segment) and
 * serve every segment as a bare CMAF media chunk — valid because the encoder is configured identically
 * for all segments. Pure ISO-BMFF box parsing; unit-tested.
 *
 * **Cross-segment timing.** Each segment is transcoded as an independent clip `[startMs, endMs)`, so its
 * encoder resets the media timeline to 0 — every segment's `tfdt` baseMediaDecodeTime is ~0. Concatenated
 * over HLS that makes all segments claim to start at t=0, so the renderer can't sequence them (Cast never
 * leaves LOADING; DLNA shows a few frames on repeat). [applyMediaTiming] rewrites each media segment's
 * `tfdt` to its true cumulative offset (and `mfhd` sequence number) so the segments form one continuous,
 * monotonic timeline. In-place edits only (no box grows), so no parent size needs fixing.
 */
object Fmp4Splitter {

    /** Byte offset of the first top-level box of [type] (a 4-char box name), or -1 if absent/truncated. */
    fun firstBoxOffset(data: ByteArray, type: String): Int {
        require(type.length == 4) { "box type must be 4 chars" }
        var offset = 0
        while (offset + 8 <= data.size) {
            val size32 = readU32(data, offset)
            val boxType = String(data, offset + 4, 4, Charsets.US_ASCII)
            val boxLength = when (size32) {
                1L -> { // 64-bit largesize follows the 8-byte header
                    if (offset + 16 > data.size) return -1
                    readU64(data, offset + 8)
                }
                0L -> (data.size - offset).toLong() // extends to end of file
                else -> size32
            }
            if (boxType == type) return offset
            if (boxLength <= 0L) return -1
            offset += boxLength.toInt()
        }
        return -1
    }

    /** The init segment: bytes before the first `moof` (or the whole input if there is no `moof`). */
    fun initSegment(fragmentedMp4: ByteArray): ByteArray {
        val moof = firstBoxOffset(fragmentedMp4, "moof")
        val end = if (moof < 0) fragmentedMp4.size else moof
        return fragmentedMp4.copyOfRange(0, end)
    }

    /** The media segment: from the first `moof` to end (a bare CMAF chunk), or empty if there is none. */
    fun mediaSegment(fragmentedMp4: ByteArray): ByteArray {
        val moof = firstBoxOffset(fragmentedMp4, "moof")
        return if (moof < 0) ByteArray(0) else fragmentedMp4.copyOfRange(moof, fragmentedMp4.size)
    }

    /**
     * Map of `track_ID` -> media `timescale` parsed from an [initSegment]'s `moov` (each `trak` via its
     * `tkhd` track id and `mdia/mdhd` timescale). Empty if the moov can't be parsed. The timescale is the
     * unit of `tfdt` baseMediaDecodeTime, so it's needed to place each segment on the shared timeline.
     */
    fun trackTimescales(initSegment: ByteArray): Map<Int, Long> {
        val moov = findChild(initSegment, 0, initSegment.size, "moov") ?: return emptyMap()
        val out = LinkedHashMap<Int, Long>()
        for (trak in childrenOfType(initSegment, moov.contentStart, moov.end, "trak")) {
            val tkhd = findChild(initSegment, trak.contentStart, trak.end, "tkhd") ?: continue
            val mdia = findChild(initSegment, trak.contentStart, trak.end, "mdia") ?: continue
            val mdhd = findChild(initSegment, mdia.contentStart, mdia.end, "mdhd") ?: continue
            val trackId = fullBoxUintAfterTimes(initSegment, tkhd, timesBeforeField = 2) ?: continue
            val timescale = fullBoxUintAfterTimes(initSegment, mdhd, timesBeforeField = 2) ?: continue
            out[trackId.toInt()] = timescale
        }
        return out
    }

    /** Codecs + video resolution declared by an init segment's `moov`, for HLS `CODECS` + diagnostics. */
    data class CodecInfo(
        /** RFC 6381 video codec string (e.g. "avc1.640028"), or a bare fourcc, or null if absent. */
        val videoCodec: String?,
        /** RFC 6381 audio codec string (e.g. "mp4a.40.2"), or null if absent. */
        val audioCodec: String?,
        val width: Int?,
        val height: Int?,
    ) {
        /** The HLS `CODECS` attribute value (comma-joined), or null when nothing was parsed. */
        val hlsCodecs: String?
            get() = listOfNotNull(videoCodec, audioCodec).takeIf { it.isNotEmpty() }?.joinToString(",")

        /** A short human string for diagnostics logs. */
        val summary: String
            get() = "video=${videoCodec ?: "?"}${if (width != null && height != null) " ${width}x$height" else ""}, " +
                "audio=${audioCodec ?: "?"}"
    }

    /**
     * Parse the [initSegment]'s `moov` for the video/audio sample-entry codecs (RFC 6381 strings where
     * possible) and the video resolution — used to build a proper HLS **master** playlist `CODECS`
     * attribute (Cast/CMAF receivers won't start an fMP4 stream without it) and for diagnostics. Returns
     * null when there's no parseable `moov`; individual fields are null when a specific box is missing.
     */
    fun codecInfo(initSegment: ByteArray): CodecInfo? {
        val moov = findChild(initSegment, 0, initSegment.size, "moov") ?: return null
        var video: String? = null
        var audio: String? = null
        var width: Int? = null
        var height: Int? = null
        for (trak in childrenOfType(initSegment, moov.contentStart, moov.end, "trak")) {
            val mdia = findChild(initSegment, trak.contentStart, trak.end, "mdia") ?: continue
            val minf = findChild(initSegment, mdia.contentStart, mdia.end, "minf") ?: continue
            val stbl = findChild(initSegment, minf.contentStart, minf.end, "stbl") ?: continue
            val stsd = findChild(initSegment, stbl.contentStart, stbl.end, "stsd") ?: continue
            // stsd is a FullBox: version(1)+flags(3)+entry_count(4), then the first SampleEntry box.
            val entry = firstChildBox(initSegment, stsd.contentStart + 8, stsd.end) ?: continue
            when (entry.type) {
                "avc1", "avc3" -> { video = video ?: avcCodecString(initSegment, entry); readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h } }
                "hvc1", "hev1" -> { video = video ?: entry.type; readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h } }
                "av01" -> { video = video ?: "av01"; readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h } }
                "vp09" -> { video = video ?: "vp09"; readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h } }
                "mp4a" -> audio = audio ?: "mp4a.40.2" // AAC-LC (the on-device encoder's only audio target)
            }
        }
        return CodecInfo(video, audio, width, height)
    }

    /** RFC 6381 "avc1.PPCCLL" from the `avcC` box inside an avc1 sample entry (bytes: profile, constraints, level). */
    private fun avcCodecString(d: ByteArray, entry: Box): String {
        // VisualSampleEntry: SampleEntry(8) + 70 fixed bytes, then child boxes (avcC ...).
        val avcC = findChild(d, entry.contentStart + 78, entry.end, "avcC")
        if (avcC == null || avcC.contentStart + 4 > avcC.end) return "avc1"
        val p = d[avcC.contentStart + 1].toInt() and 0xFF
        val c = d[avcC.contentStart + 2].toInt() and 0xFF
        val l = d[avcC.contentStart + 3].toInt() and 0xFF
        return "avc1.%02X%02X%02X".format(p, c, l)
    }

    /** width/height (uint16 each) from a VisualSampleEntry (at content offset +24 / +26), or null. */
    private fun readVisualSize(d: ByteArray, entry: Box): Pair<Int, Int>? {
        val wOff = entry.contentStart + 24
        if (wOff + 4 > entry.end) return null
        val w = ((d[wOff].toInt() and 0xFF) shl 8) or (d[wOff + 1].toInt() and 0xFF)
        val h = ((d[wOff + 2].toInt() and 0xFF) shl 8) or (d[wOff + 3].toInt() and 0xFF)
        return if (w > 0 && h > 0) w to h else null
    }

    /** First direct child box within [from, to), or null (used to read the first stsd sample entry). */
    private fun firstChildBox(d: ByteArray, from: Int, to: Int): Box? {
        var result: Box? = null
        forEachChild(d, from, to) { if (result == null) result = it }
        return result
    }

    /**
     * Rewrite a bare [mediaSegment]'s timing IN PLACE so it sits at [startSeconds] on the shared timeline.
     * The clip was transcoded independently, so its internal timeline starts at ~0; for every `moof/traf`
     * this shifts the `tfdt` baseMediaDecodeTime by `round(startSeconds * timescale[trackId])` relative to
     * the clip's own origin (the minimum tfdt per track), preserving any intra-clip multi-fragment offsets,
     * and stamps the first `moof`'s `mfhd` sequence number to [sequenceNumber]. Widths are preserved
     * (32/64-bit per box version) so no box size changes. A no-op where boxes/timescales are missing.
     */
    fun applyMediaTiming(
        mediaSegment: ByteArray,
        sequenceNumber: Int,
        startSeconds: Double,
        timescales: Map<Int, Long>,
    ): ByteArray {
        val moofs = childrenOfType(mediaSegment, 0, mediaSegment.size, "moof")
        if (moofs.isEmpty()) return mediaSegment
        // Pass 1: the clip's internal origin per track = the smallest existing tfdt base (usually 0).
        val origin = HashMap<Int, Long>()
        for (moof in moofs) {
            for (traf in childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")) {
                val trackId = trafTrackId(mediaSegment, traf) ?: continue
                val tfdt = findChild(mediaSegment, traf.contentStart, traf.end, "tfdt") ?: continue
                val base = readTfdtBase(mediaSegment, tfdt) ?: continue
                origin[trackId] = minOf(origin[trackId] ?: base, base)
            }
        }
        // Pass 2: shift every tfdt so the clip origin maps to startSeconds on the shared timeline.
        moofs.forEachIndexed { i, moof ->
            if (i == 0) {
                findChild(mediaSegment, moof.contentStart, moof.end, "mfhd")?.let { mfhd ->
                    val off = mfhd.contentStart + 4 // version(1)+flags(3) then sequence_number(4)
                    if (off + 4 <= mfhd.end) writeU32(mediaSegment, off, sequenceNumber.toLong())
                }
            }
            for (traf in childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")) {
                val trackId = trafTrackId(mediaSegment, traf) ?: continue
                val timescale = timescales[trackId] ?: continue
                val tfdt = findChild(mediaSegment, traf.contentStart, traf.end, "tfdt") ?: continue
                val base = readTfdtBase(mediaSegment, tfdt) ?: continue
                val shifted = base - (origin[trackId] ?: 0L) + Math.round(startSeconds * timescale)
                writeTfdtBase(mediaSegment, tfdt, shifted)
            }
        }
        return mediaSegment
    }

    /** track_ID from a `traf`'s `tfhd` (version(1)+flags(3) then track_ID(4)), or null. */
    private fun trafTrackId(d: ByteArray, traf: Box): Int? {
        val tfhd = findChild(d, traf.contentStart, traf.end, "tfhd") ?: return null
        return if (tfhd.contentStart + 8 <= tfhd.end) readU32(d, tfhd.contentStart + 4).toInt() else null
    }

    private fun readTfdtBase(d: ByteArray, tfdt: Box): Long? {
        val off = tfdt.contentStart + 4 // after version(1)+flags(3)
        return when (d[tfdt.contentStart].toInt() and 0xFF) {
            1 -> if (off + 8 <= tfdt.end) readU64(d, off) else null
            else -> if (off + 4 <= tfdt.end) readU32(d, off) else null
        }
    }

    private fun writeTfdtBase(d: ByteArray, tfdt: Box, value: Long) {
        val off = tfdt.contentStart + 4
        if ((d[tfdt.contentStart].toInt() and 0xFF) == 1) {
            if (off + 8 <= tfdt.end) writeU64(d, off, value.coerceAtLeast(0L))
        } else {
            if (off + 4 <= tfdt.end) writeU32(d, off, value.coerceIn(0L, 0xFFFF_FFFFL))
        }
    }

    // ----- ISO-BMFF box navigation -----

    private data class Box(val type: String, val start: Int, val contentStart: Int, val end: Int)

    /** Direct child boxes of [type] within [from, to). */
    private fun childrenOfType(d: ByteArray, from: Int, to: Int, type: String): List<Box> =
        buildList { forEachChild(d, from, to) { if (it.type == type) add(it) } }

    /** First direct child box of [type] within [from, to), or null. */
    private fun findChild(d: ByteArray, from: Int, to: Int, type: String): Box? {
        var found: Box? = null
        forEachChild(d, from, to) { if (found == null && it.type == type) found = it }
        return found
    }

    private inline fun forEachChild(d: ByteArray, from: Int, to: Int, action: (Box) -> Unit) {
        var offset = from
        while (offset + 8 <= to) {
            val size32 = readU32(d, offset)
            val type = String(d, offset + 4, 4, Charsets.US_ASCII)
            var header = 8
            val len = when (size32) {
                1L -> { if (offset + 16 > to) return; header = 16; readU64(d, offset + 8) }
                0L -> (to - offset).toLong()
                else -> size32
            }
            if (len < header) return
            val end = offset + len.toInt()
            if (end > to || end <= offset) return
            action(Box(type, offset, offset + header, end))
            offset = end
        }
    }

    /**
     * Read a 32-bit unsigned field from a full box preceded by [timesBeforeField] time fields whose width
     * is 8 bytes when the box version is 1 and 4 bytes when 0. Covers `tkhd` (track_ID after creation +
     * modification times) and `mdhd` (timescale after creation + modification times).
     */
    private fun fullBoxUintAfterTimes(d: ByteArray, box: Box, timesBeforeField: Int): Long? {
        if (box.contentStart >= box.end) return null
        val version = d[box.contentStart].toInt() and 0xFF
        val timeWidth = if (version == 1) 8 else 4
        val off = box.contentStart + 4 + timesBeforeField * timeWidth
        return if (off + 4 <= box.end) readU32(d, off) else null
    }

    private fun readU32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    private fun readU64(d: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v ushr 24).toByte(); d[o + 1] = (v ushr 16).toByte()
        d[o + 2] = (v ushr 8).toByte(); d[o + 3] = v.toByte()
    }

    private fun writeU64(d: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) d[o + i] = (v ushr (56 - i * 8)).toByte()
    }
}
