package com.videobridge.core.transcode

import java.security.MessageDigest

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
        var boxes = 0
        while (offset <= data.size - MIN_BOX_HEADER_BYTES && boxes++ < MAX_TOP_LEVEL_BOXES) {
            val remaining = data.size - offset
            val size32 = readU32(data, offset)
            val boxType = String(data, offset + 4, 4, Charsets.US_ASCII)
            var header = MIN_BOX_HEADER_BYTES
            val boxLength = when (size32) {
                1L -> {
                    header = LARGE_SIZE_BOX_HEADER_BYTES
                    readU64Within(data, offset + MIN_BOX_HEADER_BYTES, remaining.toLong()) ?: return -1
                }
                0L -> remaining.toLong() // extends to end of file
                else -> size32
            }
            // Never convert an unchecked 64-bit ISO-BMFF size to Int: hostile input used to wrap here.
            if (boxLength < header || boxLength > remaining.toLong()) return -1
            if (boxType == type) return offset
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


    /** Canonical per-track properties required to reuse one shared fMP4 init segment safely. */
    data class InitTrackFingerprint(
        val trackId: Int,
        val timescale: Long,
        val sampleDescriptionHash: String,
        val editListHash: String?,
    )

    data class InitFingerprint(val tracks: List<InitTrackFingerprint>)

    /** A published init must contain a valid `ftyp` and `moov` before the first `moof`. */
    fun isValidInitSegment(initSegment: ByteArray): Boolean {
        if (initSegment.isEmpty()) return false
        val ftyp = firstBoxOffset(initSegment, "ftyp")
        val moov = firstBoxOffset(initSegment, "moov")
        return ftyp >= 0 && moov > ftyp
    }

    /**
     * A published media resource must contain actual, parseable fragment samples. A bare `moof` +
     * `mdat` used to pass this check and was then served as an apparently successful empty segment.
     */
    fun isValidMediaSegment(mediaSegment: ByteArray): Boolean {
        val moofs = childrenOfType(mediaSegment, 0, mediaSegment.size, "moof")
        if (moofs.isEmpty() || moofs.first().start != 0) return false
        val hasMediaData = childrenOfType(mediaSegment, 0, mediaSegment.size, "mdat")
            .any { it.start > 0 && it.end > it.contentStart }
        if (!hasMediaData) return false
        return moofs.all { moof ->
            val mfhd = findChild(mediaSegment, moof.contentStart, moof.end, "mfhd")
            val hasSequence = mfhd?.let { it.contentStart + FULL_BOX_BYTES + 4 <= it.end } == true
            val trafs = childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")
            hasSequence && trafs.isNotEmpty() && trafs.all { traf ->
                val tfdt = findChild(mediaSegment, traf.contentStart, traf.end, "tfdt")
                trafTrackId(mediaSegment, traf) != null &&
                    tfdt?.let { readTfdtBase(mediaSegment, it) } != null &&
                    (trafDuration(mediaSegment, traf) ?: 0L) > 0L
            }
        }
    }

    /**
     * Confirm that a fragment contains only tracks declared by the shared init and that every declared
     * track appears somewhere in the media resource. This prevents a later independent export from
     * silently introducing a track that the published EXT-X-MAP cannot describe.
     */
    fun hasExpectedFragmentTracks(mediaSegment: ByteArray, expectedTrackIds: Set<Int>): Boolean {
        if (expectedTrackIds.isEmpty()) return false
        val observed = HashSet<Int>()
        val moofs = childrenOfType(mediaSegment, 0, mediaSegment.size, "moof")
        if (moofs.isEmpty()) return false
        for (moof in moofs) {
            val trafs = childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")
            if (trafs.isEmpty()) return false
            for (traf in trafs) {
                val trackId = trafTrackId(mediaSegment, traf) ?: return false
                if (trackId !in expectedTrackIds) return false
                observed += trackId
            }
        }
        return observed == expectedTrackIds
    }

    /**
     * Fingerprint the properties an HLS `EXT-X-MAP` shares across independently exported fragments.
     * Movie-level duration is deliberately excluded, while track IDs, timescales, sample descriptions,
     * and edit lists are compared. A missing/duplicate property is rejected rather than guessed.
     */
    fun initFingerprint(initSegment: ByteArray): InitFingerprint? {
        if (!isValidInitSegment(initSegment)) return null
        val moov = findChild(initSegment, 0, initSegment.size, "moov") ?: return null
        val tracks = ArrayList<InitTrackFingerprint>()
        val seenTrackIds = HashSet<Int>()
        for (trak in childrenOfType(initSegment, moov.contentStart, moov.end, "trak")) {
            val tkhd = findChild(initSegment, trak.contentStart, trak.end, "tkhd") ?: return null
            val mdia = findChild(initSegment, trak.contentStart, trak.end, "mdia") ?: return null
            val mdhd = findChild(initSegment, mdia.contentStart, mdia.end, "mdhd") ?: return null
            val minf = findChild(initSegment, mdia.contentStart, mdia.end, "minf") ?: return null
            val stbl = findChild(initSegment, minf.contentStart, minf.end, "stbl") ?: return null
            val stsd = findChild(initSegment, stbl.contentStart, stbl.end, "stsd") ?: return null
            val rawTrackId = fullBoxUintAfterTimes(initSegment, tkhd, timesBeforeField = 2) ?: return null
            val timescale = fullBoxUintAfterTimes(initSegment, mdhd, timesBeforeField = 2) ?: return null
            if (rawTrackId !in 1L..Int.MAX_VALUE.toLong() || timescale <= 0L) return null
            val trackId = rawTrackId.toInt()
            if (!seenTrackIds.add(trackId)) return null
            val editHash = findChild(initSegment, trak.contentStart, trak.end, "edts")
                ?.let { sha256(initSegment, it.start, it.end) }
            tracks += InitTrackFingerprint(
                trackId = trackId,
                timescale = timescale,
                sampleDescriptionHash = sha256(initSegment, stsd.start, stsd.end),
                editListHash = editHash,
            )
        }
        return tracks.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.trackId }
            ?.let(::InitFingerprint)
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
            if (trackId !in 1L..Int.MAX_VALUE.toLong() || timescale <= 0L) continue
            if (out.put(trackId.toInt(), timescale) != null) return emptyMap()
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
                "avc1", "avc3" -> {
                    video = video ?: avcCodecString(initSegment, entry)
                    readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h }
                }
                "hvc1", "hev1" -> {
                    video = video ?: hevcCodecString(initSegment, entry)
                    readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h }
                }
                // These modes are feature-gated in the active client pipeline, but retain their sample
                // entry names for diagnostics if an externally produced init is inspected.
                "av01", "vp09" -> {
                    video = video ?: entry.type
                    readVisualSize(initSegment, entry)?.let { (w, h) -> width = width ?: w; height = height ?: h }
                }
                "mp4a" -> audio = audio ?: aacCodecString(initSegment, entry)
            }
        }
        return CodecInfo(video, audio, width, height)
    }

    /** RFC 6381 `avc1`/`avc3`.PPCCLL from the avcC profile/constraint/level bytes. */
    private fun avcCodecString(d: ByteArray, entry: Box): String {
        val avcC = findChild(d, entry.contentStart + VISUAL_SAMPLE_ENTRY_FIXED_BYTES, entry.end, "avcC")
            ?: return entry.type
        if (avcC.contentStart + 4 > avcC.end) return entry.type
        val p = d[avcC.contentStart + 1].toInt() and 0xFF
        val c = d[avcC.contentStart + 2].toInt() and 0xFF
        val l = d[avcC.contentStart + 3].toInt() and 0xFF
        return "${entry.type}.%02X%02X%02X".format(p, c, l)
    }

    /** RFC 6381 HEVC identifier from hvcC, including profile, compatibility, tier, level and constraints. */
    private fun hevcCodecString(d: ByteArray, entry: Box): String {
        val hvcC = findChild(d, entry.contentStart + VISUAL_SAMPLE_ENTRY_FIXED_BYTES, entry.end, "hvcC")
            ?: return entry.type
        if (hvcC.contentStart + 13 > hvcC.end) return entry.type
        val profileTier = d[hvcC.contentStart + 1].toInt() and 0xFF
        val profileSpace = when ((profileTier ushr 6) and 0x03) { 1 -> "A"; 2 -> "B"; 3 -> "C"; else -> "" }
        val profileIdc = profileTier and 0x1F
        val compatibility = hexWithoutLeadingZeros(d, hvcC.contentStart + 2, 4)
        val tier = if ((profileTier and 0x20) != 0) "H" else "L"
        val level = d[hvcC.contentStart + 12].toInt() and 0xFF
        val constraints = hexWithoutTrailingZeroBytes(d, hvcC.contentStart + 6, 6)
        return buildString {
            append(entry.type).append('.').append(profileSpace).append(profileIdc)
            append('.').append(compatibility).append('.').append(tier).append(level)
            if (constraints.isNotEmpty()) append('.').append(constraints)
        }
    }

    /** Actual AAC AudioSpecificConfig object type from esds; null instead of falsely assuming AAC-LC. */
    private fun aacCodecString(d: ByteArray, entry: Box): String? {
        val esds = findChild(d, entry.contentStart + AUDIO_SAMPLE_ENTRY_FIXED_BYTES, entry.end, "esds") ?: return null
        val descriptor = findDescriptor(d, esds.contentStart + FULL_BOX_BYTES, esds.end, 0x05) ?: return null
        if (descriptor.first >= descriptor.second) return null
        val first = d[descriptor.first].toInt() and 0xFF
        var objectType = first ushr 3
        if (objectType == 31) {
            if (descriptor.first + 1 >= descriptor.second) return null
            objectType = 32 + (((first and 0x07) shl 3) or ((d[descriptor.first + 1].toInt() and 0xFF) ushr 5))
        }
        return objectType.takeIf { it in 1..63 }?.let { "mp4a.40.$it" }
    }

    /** Byte range of a descriptor payload, with bounded ISO/IEC 14496-1 variable-length parsing. */
    private fun findDescriptor(d: ByteArray, from: Int, to: Int, targetTag: Int): Pair<Int, Int>? {
        var offset = from.coerceIn(0, to)
        var scanned = 0
        while (offset < to && scanned++ < MAX_DESCRIPTOR_SCAN_BYTES) {
            val tag = d[offset++].toInt() and 0xFF
            var length = 0
            var bytes = 0
            var terminated = false
            while (offset < to && bytes++ < MAX_DESCRIPTOR_LENGTH_BYTES) {
                val b = d[offset++].toInt() and 0xFF
                if (length > (Int.MAX_VALUE ushr 7)) return null
                length = (length shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) {
                    terminated = true
                    break
                }
            }
            if (!terminated || length > to - offset) return null
            val payloadStart = offset
            val end = payloadStart + length
            if (tag == targetTag) return payloadStart to end
            val childStart = when (tag) {
                // ES_Descriptor: ES_ID(2), flags(1), then optional dependence/URL/OCR fields.
                0x03 -> esDescriptorChildStart(d, payloadStart, end)
                // DecoderConfigDescriptor fixed fields precede its nested DecoderSpecificInfo.
                0x04 -> (payloadStart + DECODER_CONFIG_FIXED_BYTES).takeIf { it <= end }
                else -> null
            }
            if (childStart != null) {
                findDescriptor(d, childStart, end, targetTag)?.let { return it }
            }
            offset = end
        }
        return null
    }

    private fun esDescriptorChildStart(d: ByteArray, start: Int, end: Int): Int? {
        if (start + 3 > end) return null
        var offset = start + 3
        val flags = d[start + 2].toInt() and 0xFF
        if ((flags and 0x80) != 0) offset += 2
        if ((flags and 0x40) != 0) {
            if (offset >= end) return null
            offset += 1 + (d[offset].toInt() and 0xFF)
        }
        if ((flags and 0x20) != 0) offset += 2
        return offset.takeIf { it <= end }
    }

    private fun hexWithoutLeadingZeros(d: ByteArray, offset: Int, length: Int): String {
        val hex = (0 until length).joinToString("") { "%02X".format(d[offset + it].toInt() and 0xFF) }
        return hex.trimStart('0').ifEmpty { "0" }
    }

    private fun hexWithoutTrailingZeroBytes(d: ByteArray, offset: Int, length: Int): String {
        var end = length
        while (end > 0 && d[offset + end - 1].toInt() == 0) end--
        return (0 until end).joinToString("") { "%02X".format(d[offset + it].toInt() and 0xFF) }
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
     * Every moof receives a distinct mfhd sequence number; callers reserve a sufficiently wide base range.
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
            findChild(mediaSegment, moof.contentStart, moof.end, "mfhd")?.let { mfhd ->
                val off = mfhd.contentStart + FULL_BOX_BYTES
                if (off + 4 <= mfhd.end) {
                    val sequence = (sequenceNumber.toLong() + i).coerceIn(1L, UINT32_MAX)
                    writeU32(mediaSegment, off, sequence)
                }
            }
            for (traf in childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")) {
                val trackId = trafTrackId(mediaSegment, traf) ?: continue
                val timescale = timescales[trackId]?.takeIf { it > 0 } ?: continue
                val tfdt = findChild(mediaSegment, traf.contentStart, traf.end, "tfdt") ?: continue
                val base = readTfdtBase(mediaSegment, tfdt) ?: continue
                val relative = (base - (origin[trackId] ?: base)).coerceAtLeast(0L)
                val timelineStart = timelineTicks(startSeconds, timescale) ?: continue
                val shifted = if (relative > Long.MAX_VALUE - timelineStart) Long.MAX_VALUE else relative + timelineStart
                writeTfdtBase(mediaSegment, tfdt, shifted)
            }
        }
        return mediaSegment
    }

    /**
     * Parse the sum of sample decode durations in each traf/trun. Returns the longest track duration,
     * normally the video duration, or null when the fragment omits enough timing to measure safely.
     */
    fun mediaDurationSeconds(mediaSegment: ByteArray, timescales: Map<Int, Long>): Double? {
        if (timescales.isEmpty()) return null
        val totals = LinkedHashMap<Int, Long>()
        for (moof in childrenOfType(mediaSegment, 0, mediaSegment.size, "moof")) {
            for (traf in childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")) {
                val trackId = trafTrackId(mediaSegment, traf) ?: return null
                val timescale = timescales[trackId]?.takeIf { it > 0L } ?: return null
                val duration = trafDuration(mediaSegment, traf) ?: return null
                if (duration <= 0L) return null
                val previous = totals[trackId] ?: 0L
                if (previous > Long.MAX_VALUE - duration) return null
                totals[trackId] = previous + duration
                // Keep the lookup explicit: an unmatched traf must not be silently interpreted in another scale.
                if (timescale <= 0L) return null
            }
        }
        val durations = totals.map { (trackId, ticks) ->
            val timescale = timescales[trackId] ?: return null
            ticks.toDouble() / timescale.toDouble()
        }
        return durations.maxOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Verify that every declared track exactly occupies the planned decode-time interval after timing
     * has been rewritten. Looking only at the longest track can hide an audio gap or overlap even when
     * the video duration looks correct; the next independently exported segment would then start at a
     * conflicting tfdt. Reject that fragment rather than publishing a discontinuous CMAF timeline.
     */
    fun hasContinuousTrackTimeline(
        mediaSegment: ByteArray,
        timescales: Map<Int, Long>,
        startSeconds: Double,
        durationSeconds: Double,
    ): Boolean {
        if (timescales.isEmpty() || !startSeconds.isFinite() || startSeconds < 0.0 ||
            !durationSeconds.isFinite() || durationSeconds <= 0.0
        ) {
            return false
        }
        val endSeconds = startSeconds + durationSeconds
        if (!endSeconds.isFinite() || endSeconds < startSeconds) return false

        data class DecodeInterval(val start: Long, val duration: Long)
        val intervals = HashMap<Int, MutableList<DecodeInterval>>()
        for (moof in childrenOfType(mediaSegment, 0, mediaSegment.size, "moof")) {
            for (traf in childrenOfType(mediaSegment, moof.contentStart, moof.end, "traf")) {
                val trackId = trafTrackId(mediaSegment, traf) ?: return false
                val timescale = timescales[trackId]?.takeIf { it > 0L } ?: return false
                val tfdt = findChild(mediaSegment, traf.contentStart, traf.end, "tfdt") ?: return false
                val start = readTfdtBase(mediaSegment, tfdt) ?: return false
                val duration = trafDuration(mediaSegment, traf) ?: return false
                if (duration <= 0L || start > Long.MAX_VALUE - duration || timescale <= 0L) return false
                intervals.getOrPut(trackId) { ArrayList() }.add(DecodeInterval(start, duration))
            }
        }
        if (intervals.keys != timescales.keys) return false

        for ((trackId, timescale) in timescales) {
            if (timescale <= 0L) return false
            val expectedStart = timelineTicks(startSeconds, timescale) ?: return false
            val expectedEnd = timelineTicks(endSeconds, timescale) ?: return false
            var cursor = expectedStart
            val trackIntervals = intervals[trackId] ?: return false
            for (interval in trackIntervals) {
                if (interval.start != cursor || cursor > Long.MAX_VALUE - interval.duration) return false
                cursor += interval.duration
            }
            if (cursor != expectedEnd) return false
        }
        return true
    }

    private fun trafDuration(d: ByteArray, traf: Box): Long? {
        val tfhd = findChild(d, traf.contentStart, traf.end, "tfhd") ?: return null
        val defaultDuration = tfhdDefaultSampleDuration(d, tfhd)
        var total = 0L
        var sawTrun = false
        for (trun in childrenOfType(d, traf.contentStart, traf.end, "trun")) {
            val duration = trunDuration(d, trun, defaultDuration) ?: return null
            if (total > Long.MAX_VALUE - duration) return null
            total += duration
            sawTrun = true
        }
        return total.takeIf { sawTrun }
    }

    private fun tfhdDefaultSampleDuration(d: ByteArray, tfhd: Box): Long? {
        if (tfhd.contentStart + 8 > tfhd.end) return null
        val flags = fullBoxFlags(d, tfhd.contentStart)
        var offset = tfhd.contentStart + 8 // full box + track_ID
        if ((flags and 0x000001) != 0) offset += 8 // base_data_offset
        if ((flags and 0x000002) != 0) offset += 4 // sample_description_index
        return if ((flags and 0x000008) != 0) {
            if (offset + 4 <= tfhd.end) readU32(d, offset).takeIf { it > 0L } else null
        } else {
            null
        }
    }

    private fun trunDuration(d: ByteArray, trun: Box, defaultDuration: Long?): Long? {
        if (trun.contentStart + 8 > trun.end) return null
        val flags = fullBoxFlags(d, trun.contentStart)
        val sampleCount = readU32(d, trun.contentStart + 4)
        if (sampleCount > MAX_SAMPLES_PER_TRUN.toLong()) return null
        var offset = trun.contentStart + 8
        if ((flags and 0x000001) != 0) offset += 4 // data_offset
        if ((flags and 0x000004) != 0) offset += 4 // first_sample_flags
        if (offset > trun.end) return null
        val hasDuration = (flags and 0x000100) != 0
        val hasSize = (flags and 0x000200) != 0
        val hasFlags = (flags and 0x000400) != 0
        val hasCompositionOffset = (flags and 0x000800) != 0
        var total = 0L
        repeat(sampleCount.toInt()) {
            val duration = if (hasDuration) {
                if (offset + 4 > trun.end) return null
                readU32(d, offset).also { offset += 4 }
            } else {
                defaultDuration ?: return null
            }
            if (duration <= 0L || total > Long.MAX_VALUE - duration) return null
            total += duration
            if (hasSize) { if (offset + 4 > trun.end) return null; offset += 4 }
            if (hasFlags) { if (offset + 4 > trun.end) return null; offset += 4 }
            if (hasCompositionOffset) { if (offset + 4 > trun.end) return null; offset += 4 }
        }
        return total
    }

    /** track_ID from a `traf`'s `tfhd` (version(1)+flags(3) then track_ID(4)), or null. */
    private fun trafTrackId(d: ByteArray, traf: Box): Int? {
        val tfhd = findChild(d, traf.contentStart, traf.end, "tfhd") ?: return null
        if (tfhd.contentStart + 8 > tfhd.end) return null
        return readU32(d, tfhd.contentStart + FULL_BOX_BYTES)
            .takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            ?.toInt()
    }

    private fun readTfdtBase(d: ByteArray, tfdt: Box): Long? {
        if (tfdt.contentStart + FULL_BOX_BYTES > tfdt.end) return null
        val off = tfdt.contentStart + FULL_BOX_BYTES
        return when (d[tfdt.contentStart].toInt() and 0xFF) {
            1 -> if (off + 8 <= tfdt.end) readU64Within(d, off, Long.MAX_VALUE) else null
            0 -> if (off + 4 <= tfdt.end) readU32(d, off) else null
            else -> null
        }
    }

    private fun timelineTicks(seconds: Double, timescale: Long): Long? {
        if (timescale <= 0L || !seconds.isFinite() || seconds < 0.0) return null
        val scaled = seconds * timescale.toDouble()
        if (!scaled.isFinite() || scaled < 0.0) return null
        return if (scaled >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else Math.round(scaled)
    }

    private fun writeTfdtBase(d: ByteArray, tfdt: Box, value: Long) {
        if (tfdt.contentStart + FULL_BOX_BYTES > tfdt.end) {
            throw IllegalArgumentException("truncated tfdt box")
        }
        val off = tfdt.contentStart + FULL_BOX_BYTES
        when (d[tfdt.contentStart].toInt() and 0xFF) {
            1 -> {
                if (off + 8 > tfdt.end) throw IllegalArgumentException("truncated version-1 tfdt box")
                writeU64(d, off, value.coerceAtLeast(0L))
            }
            0 -> {
                require(value in 0L..UINT32_MAX) {
                    "version-0 tfdt cannot represent this segment's continuous timeline"
                }
                if (off + 4 > tfdt.end) throw IllegalArgumentException("truncated version-0 tfdt box")
                writeU32(d, off, value)
            }
            else -> throw IllegalArgumentException("unsupported tfdt version")
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
        if (from < 0 || to < from || to > d.size) return
        var offset = from
        var boxes = 0
        while (offset <= to - MIN_BOX_HEADER_BYTES && boxes++ < MAX_CHILD_BOXES) {
            val remaining = to - offset
            val size32 = readU32(d, offset)
            val type = String(d, offset + 4, 4, Charsets.US_ASCII)
            var header = MIN_BOX_HEADER_BYTES
            val length = when (size32) {
                1L -> {
                    header = LARGE_SIZE_BOX_HEADER_BYTES
                    readU64Within(d, offset + MIN_BOX_HEADER_BYTES, remaining.toLong()) ?: return
                }
                0L -> remaining.toLong()
                else -> size32
            }
            if (length < header || length > remaining.toLong()) return
            val end = offset + length.toInt()
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
        if (timesBeforeField < 0 || box.contentStart + FULL_BOX_BYTES > box.end) return null
        val version = d[box.contentStart].toInt() and 0xFF
        val timeWidth = if (version == 1) 8 else 4
        val off = box.contentStart + FULL_BOX_BYTES + timesBeforeField * timeWidth
        return if (off >= box.contentStart && off + 4 <= box.end) readU32(d, off) else null
    }

    private fun fullBoxFlags(d: ByteArray, contentStart: Int): Int =
        ((d[contentStart + 1].toInt() and 0xFF) shl 16) or
            ((d[contentStart + 2].toInt() and 0xFF) shl 8) or
            (d[contentStart + 3].toInt() and 0xFF)

    private fun readU32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    /** Read only unsigned 64-bit values representable by this JVM implementation and [maxValue]. */
    private fun readU64Within(d: ByteArray, o: Int, maxValue: Long): Long? {
        if (maxValue < 0L || o < 0 || o > d.size - 8 || (d[o].toInt() and 0x80) != 0) return null
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (d[o + i].toLong() and 0xFF)
        return value.takeIf { it <= maxValue }
    }

    private fun writeU32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v ushr 24).toByte(); d[o + 1] = (v ushr 16).toByte()
        d[o + 2] = (v ushr 8).toByte(); d[o + 3] = v.toByte()
    }

    private fun writeU64(d: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) d[o + i] = (v ushr (56 - i * 8)).toByte()
    }

    private fun sha256(d: ByteArray, start: Int, end: Int): String {
        if (start < 0 || end < start || end > d.size) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(d, start, end - start)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val MIN_BOX_HEADER_BYTES = 8
    private const val LARGE_SIZE_BOX_HEADER_BYTES = 16
    private const val FULL_BOX_BYTES = 4
    private const val VISUAL_SAMPLE_ENTRY_FIXED_BYTES = 78
    private const val AUDIO_SAMPLE_ENTRY_FIXED_BYTES = 28
    private const val UINT32_MAX = 0xFFFF_FFFFL
    private const val MAX_TOP_LEVEL_BOXES = 4_096
    private const val MAX_CHILD_BOXES = 16_384
    private const val MAX_SAMPLES_PER_TRUN = 100_000
    private const val MAX_DESCRIPTOR_LENGTH_BYTES = 4
    private const val MAX_DESCRIPTOR_SCAN_BYTES = 4_096
    private const val DECODER_CONFIG_FIXED_BYTES = 13
}
