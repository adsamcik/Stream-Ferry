package com.videobridge.core

import com.videobridge.core.transcode.Fmp4Splitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Fmp4SplitterTest {

    /** Build a top-level ISO-BMFF box: 4-byte big-endian size + 4-char type + payload. */
    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte(); out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte(); out[3] = size.toByte()
        type.forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        payload.copyInto(out, 8)
        return out
    }

    private val ftyp = box("ftyp", byteArrayOf(1, 2, 3, 4))
    private val moov = box("moov", byteArrayOf(5, 6, 7, 8))
    private val moof = box("moof", byteArrayOf(9, 10))
    private val mdat = box("mdat", byteArrayOf(11, 12, 13))
    private val fragmented = ftyp + moov + moof + mdat

    @Test fun firstBoxOffsetFindsEachBox() {
        assertEquals(0, Fmp4Splitter.firstBoxOffset(fragmented, "ftyp"))
        assertEquals(ftyp.size, Fmp4Splitter.firstBoxOffset(fragmented, "moov"))
        assertEquals(ftyp.size + moov.size, Fmp4Splitter.firstBoxOffset(fragmented, "moof"))
        assertEquals(ftyp.size + moov.size + moof.size, Fmp4Splitter.firstBoxOffset(fragmented, "mdat"))
        assertEquals(-1, Fmp4Splitter.firstBoxOffset(fragmented, "free"))
    }

    @Test fun initSegmentIsEverythingBeforeFirstMoof() {
        val init = Fmp4Splitter.initSegment(fragmented)
        assertEquals(ftyp.size + moov.size, init.size)
        assertTrue((ftyp + moov).contentEquals(init))
    }

    @Test fun mediaSegmentIsFromFirstMoofToEnd() {
        val media = Fmp4Splitter.mediaSegment(fragmented)
        assertEquals(moof.size + mdat.size, media.size)
        assertTrue((moof + mdat).contentEquals(media))
    }

    @Test fun noMoofYieldsWholeInitAndEmptyMedia() {
        val noMoof = ftyp + moov
        assertTrue(noMoof.contentEquals(Fmp4Splitter.initSegment(noMoof)))
        assertEquals(0, Fmp4Splitter.mediaSegment(noMoof).size)
    }

    @Test fun initThenMediaReconstructsOriginal() {
        val reconstructed = Fmp4Splitter.initSegment(fragmented) + Fmp4Splitter.mediaSegment(fragmented)
        assertTrue(fragmented.contentEquals(reconstructed))
    }

    // ----- cross-segment timing (tfdt / mfhd rewrite) -----

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u64(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }
    private val verFlags0 = ByteArray(4) // version 0 + flags
    private fun ByteArray.contains(sub: ByteArray): Boolean {
        if (sub.isEmpty() || sub.size > size) return false
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return true
        }
        return false
    }

    // tkhd v0: [ver/flags 4][creation 4][modification 4][track_ID 4]...
    private fun tkhd(trackId: Long) = box("tkhd", verFlags0 + u32(0) + u32(0) + u32(trackId))
    // mdhd v0: [ver/flags 4][creation 4][modification 4][timescale 4][duration 4]
    private fun mdhd(timescale: Long) = box("mdhd", verFlags0 + u32(0) + u32(0) + u32(timescale) + u32(0))
    private fun trak(trackId: Long, timescale: Long) = box("trak", tkhd(trackId) + box("mdia", mdhd(timescale)))

    @Test fun trackTimescalesParsesEachTrack() {
        val init = box("ftyp", byteArrayOf(0, 0, 0, 0)) + box("moov", trak(1, 90_000) + trak(2, 48_000))
        val ts = Fmp4Splitter.trackTimescales(init)
        assertEquals(90_000L, ts[1])
        assertEquals(48_000L, ts[2])
    }

    // tfhd: [ver/flags 4][track_ID 4]; tfdt v1: [01 flags][baseMediaDecodeTime 8]; v0: [00 flags][base 4].
    private fun tfhd(trackId: Long) = box("tfhd", verFlags0 + u32(trackId))
    private fun tfdtV1(base: Long) = box("tfdt", byteArrayOf(1, 0, 0, 0) + u64(base))
    private fun tfdtV0(base: Long) = box("tfdt", verFlags0 + u32(base))

    private fun trunWithDurations(vararg durations: Long): ByteArray =
        box(
            "trun",
            byteArrayOf(0, 0, 1, 0) + u32(durations.size.toLong()) +
                durations.fold(ByteArray(0)) { bytes, duration -> bytes + u32(duration) },
        )

    private fun timedTraf(trackId: Long, tfdt: ByteArray, vararg durations: Long): ByteArray =
        box("traf", tfhd(trackId) + tfdt + trunWithDurations(*durations))

    private fun segment(seq: Long, videoTfdt: ByteArray, audioTfdt: ByteArray): ByteArray {
        val mfhd = box("mfhd", verFlags0 + u32(seq))
        val trafV = box("traf", tfhd(1) + videoTfdt)
        val trafA = box("traf", tfhd(2) + audioTfdt)
        return box("moof", mfhd + trafV + trafA) + box("mdat", byteArrayOf(1, 2, 3))
    }

    @Test fun applyMediaTimingRewritesTfdtAndSequence() {
        val seg = segment(seq = 1, videoTfdt = tfdtV1(0), audioTfdt = tfdtV0(0))
        val originalSize = seg.size
        Fmp4Splitter.applyMediaTiming(seg, sequenceNumber = 42, startSeconds = 10.0, timescales = mapOf(1 to 90_000L, 2 to 48_000L))
        assertEquals(originalSize, seg.size) // in-place: no box grows
        assertTrue(seg.contains(u64(900_000L)), "video tfdt (v1) should become 10s * 90000 = 900000")
        assertTrue(seg.contains(u32(480_000L)), "audio tfdt (v0) should become 10s * 48000 = 480000")
        assertTrue(seg.contains(u32(42L)), "mfhd sequence number should be rewritten")
    }

    @Test fun applyMediaTimingFirstSegmentIsZero() {
        val seg = segment(seq = 1, videoTfdt = tfdtV1(123), audioTfdt = tfdtV0(456))
        Fmp4Splitter.applyMediaTiming(seg, sequenceNumber = 1, startSeconds = 0.0, timescales = mapOf(1 to 90_000L, 2 to 48_000L))
        // Segment 0 sits at t=0: both tfdt base times become 0 regardless of the encoder's reset value.
        assertTrue(seg.contains(u64(0L)))
        assertTrue(seg.contains(u32(0L)))
    }

    @Test fun applyMediaTimingNoTimescalesLeavesTfdtButStillSetsSequence() {
        val seg = segment(seq = 1, videoTfdt = tfdtV1(0), audioTfdt = tfdtV0(0))
        val before = seg.copyOf()
        Fmp4Splitter.applyMediaTiming(seg, sequenceNumber = 7, startSeconds = 10.0, timescales = emptyMap())
        assertEquals(before.size, seg.size)
        assertTrue(seg.contains(u32(7L)), "sequence is rewritten even without timescales")
        // No timescale for any track -> tfdt left untouched (still zero), so no timeline corruption.
        assertTrue(seg.contains(u64(0L)))
    }

    @Test fun applyMediaTimingPreservesIntraClipFragmentOffsets() {
        // A clip the muxer split into two fragments: video tfdt 0 and 90000 (a 1s intra-clip gap @ 90000).
        // Shifted to startSeconds=10s they must become 900000 and 990000 — the 1s gap is preserved.
        val moof0 = box("moof", box("mfhd", verFlags0 + u32(1)) + box("traf", tfhd(1) + tfdtV1(0)))
        val moof1 = box("moof", box("mfhd", verFlags0 + u32(2)) + box("traf", tfhd(1) + tfdtV1(90_000)))
        val seg = moof0 + box("mdat", byteArrayOf(1)) + moof1 + box("mdat", byteArrayOf(2))
        Fmp4Splitter.applyMediaTiming(seg, sequenceNumber = 6, startSeconds = 10.0, timescales = mapOf(1 to 90_000L))
        assertTrue(seg.contains(u64(900_000L)), "first fragment shifts to 10s")
        assertTrue(seg.contains(u64(990_000L)), "second fragment keeps its +1s intra-clip offset")
    }

    @Test fun continuousTrackTimelineAcceptsExactPerTrackCadence() {
        val media = box(
            "moof",
            box("mfhd", verFlags0 + u32(1)) +
                timedTraf(1, tfdtV1(0), 180_000) +
                timedTraf(2, tfdtV0(0), 96_000),
        ) + box("mdat", byteArrayOf(1))
        val timescales = mapOf(1 to 90_000L, 2 to 48_000L)
        Fmp4Splitter.applyMediaTiming(media, sequenceNumber = 5, startSeconds = 10.0, timescales = timescales)
        assertTrue(Fmp4Splitter.hasContinuousTrackTimeline(media, timescales, startSeconds = 10.0, durationSeconds = 2.0))
    }

    @Test fun continuousTrackTimelineRejectsDriftOnAShorterTrack() {
        val media = box(
            "moof",
            box("mfhd", verFlags0 + u32(1)) +
                timedTraf(1, tfdtV1(0), 180_000) +
                timedTraf(2, tfdtV0(0), 96_001),
        ) + box("mdat", byteArrayOf(1))
        assertFalse(
            Fmp4Splitter.hasContinuousTrackTimeline(
                media,
                mapOf(1 to 90_000L, 2 to 48_000L),
                startSeconds = 0.0,
                durationSeconds = 2.0,
            ),
        )
    }

    @Test fun continuousTrackTimelineRejectsAnIntraTrackGap() {
        val first = box(
            "moof",
            box("mfhd", verFlags0 + u32(1)) + timedTraf(1, tfdtV1(0), 90_000),
        )
        val second = box(
            "moof",
            box("mfhd", verFlags0 + u32(2)) + timedTraf(1, tfdtV1(90_001), 90_000),
        )
        val media = first + box("mdat", byteArrayOf(1)) + second + box("mdat", byteArrayOf(2))
        assertFalse(
            Fmp4Splitter.hasContinuousTrackTimeline(
                media,
                mapOf(1 to 90_000L),
                startSeconds = 0.0,
                durationSeconds = 2.0,
            ),
        )
    }

    // ----- codecInfo (init-segment codec/resolution parse for the HLS master playlist) -----

    private fun stsd(entry: ByteArray) = box("stsd", verFlags0 + u32(1) + entry)

    private fun avc1(width: Int, height: Int, profile: Int, constraints: Int, level: Int): ByteArray {
        val fixed = ByteArray(78) // SampleEntry(8) + VisualSampleEntry fixed(70); width@24, height@26
        fixed[24] = (width ushr 8).toByte(); fixed[25] = width.toByte()
        fixed[26] = (height ushr 8).toByte(); fixed[27] = height.toByte()
        val avcC = box("avcC", byteArrayOf(1, profile.toByte(), constraints.toByte(), level.toByte()))
        return box("avc1", fixed + avcC)
    }

    private fun videoTrak(entry: ByteArray) =
        box("trak", box("mdia", box("minf", box("stbl", stsd(entry)))))
    private fun aacEsds(audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x10)) =
        box("esds", verFlags0 + byteArrayOf(0x05, audioSpecificConfig.size.toByte()) + audioSpecificConfig)

    private fun audioTrak() =
        box("trak", box("mdia", box("minf", box("stbl", stsd(box("mp4a", ByteArray(28) + aacEsds()))))))

    private fun hvc1(width: Int, height: Int): ByteArray {
        val fixed = ByteArray(78)
        fixed[24] = (width ushr 8).toByte(); fixed[25] = width.toByte()
        fixed[26] = (height ushr 8).toByte(); fixed[27] = height.toByte()
        // hvcC: Main profile, compatibility 6, constraint B0, level 120.
        val hvcC = box("hvcC", byteArrayOf(1, 1, 0, 0, 0, 6, 0xB0.toByte(), 0, 0, 0, 0, 0, 120.toByte()))
        return box("hvc1", fixed + hvcC)
    }

    private fun fingerprintTrak(trackId: Long, timescale: Long, entry: ByteArray): ByteArray =
        box("trak", tkhd(trackId) + box("mdia", mdhd(timescale) + box("minf", box("stbl", stsd(entry)))))

    @Test fun codecInfoParsesAvcAndAacAndResolution() {
        val init = box("ftyp", ByteArray(4)) +
            box("moov", videoTrak(avc1(1280, 720, 0x64, 0x00, 0x28)) + audioTrak())
        val info = Fmp4Splitter.codecInfo(init)!!
        assertEquals("avc1.640028", info.videoCodec)
        assertEquals("mp4a.40.2", info.audioCodec)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals("avc1.640028,mp4a.40.2", info.hlsCodecs)
    }

    @Test fun codecInfoPreservesAvc3SampleEntryPrefix() {
        val entry = avc1(1280, 720, 0x64, 0x00, 0x28).also { bytes ->
            "avc3".forEachIndexed { i, c -> bytes[4 + i] = c.code.toByte() }
        }
        val init = box("ftyp", ByteArray(4)) + box("moov", videoTrak(entry))
        assertEquals("avc3.640028", Fmp4Splitter.codecInfo(init)?.videoCodec)
    }

    @Test fun codecInfoNullWhenNoMoov() {
        assertEquals(null, Fmp4Splitter.codecInfo(box("ftyp", ByteArray(4))))
    }

    @Test fun codecInfoParsesHevcConfigurationRatherThanBareSampleEntry() {
        val init = box("ftyp", ByteArray(4)) + box("moov", videoTrak(hvc1(1920, 1080)) + audioTrak())
        val info = Fmp4Splitter.codecInfo(init)!!
        assertEquals("hvc1.1.6.L120.B0", info.videoCodec)
        assertEquals("mp4a.40.2", info.audioCodec)
    }

    @Test fun initFingerprintRejectsChangedSampleDescription() {
        val first = box("ftyp", ByteArray(4)) + box("moov", fingerprintTrak(1, 90_000, avc1(1280, 720, 0x64, 0, 0x28)))
        val changed = box("ftyp", ByteArray(4)) + box("moov", fingerprintTrak(1, 90_000, avc1(1280, 720, 0x4D, 0, 0x1F)))
        val firstFingerprint = Fmp4Splitter.initFingerprint(first)
        assertEquals(firstFingerprint, Fmp4Splitter.initFingerprint(first.copyOf()))
        assertNotEquals(firstFingerprint, Fmp4Splitter.initFingerprint(changed))
    }

    @Test fun rejectsUnrepresentableLargeBoxLength() {
        val malformed = ByteArray(16)
        malformed[3] = 1 // large-size box
        "moof".forEachIndexed { i, c -> malformed[4 + i] = c.code.toByte() }
        malformed[8] = 0x80.toByte() // unsigned 64-bit value above Long.MAX_VALUE
        assertEquals(-1, Fmp4Splitter.firstBoxOffset(malformed, "moof"))
    }

    @Test fun rejectsAnEmptyMoofAndMdatAsMedia() {
        assertFalse(Fmp4Splitter.isValidMediaSegment(box("moof") + box("mdat")))
    }

    @Test fun timingRefusesVersionZeroTfdtOverflowRatherThanClamping() {
        val seg = segment(seq = 1, videoTfdt = tfdtV0(0), audioTfdt = tfdtV0(0))
        assertFailsWith<IllegalArgumentException> {
            Fmp4Splitter.applyMediaTiming(
                seg,
                sequenceNumber = 1,
                startSeconds = 50_000.0,
                timescales = mapOf(1 to 90_000L, 2 to 48_000L),
            )
        }
    }

    @Test fun mediaDurationUsesTrunSampleDurations() {
        val trun = box("trun", byteArrayOf(0, 0, 1, 0) + u32(2) + u32(500) + u32(1_000))
        val media = box("moof", box("traf", tfhd(1) + trun)) + box("mdat", byteArrayOf(1))
        assertEquals(1.5, Fmp4Splitter.mediaDurationSeconds(media, mapOf(1 to 1_000L)))
    }
}
