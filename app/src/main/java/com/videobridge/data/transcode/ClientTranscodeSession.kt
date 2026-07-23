package com.videobridge.data.transcode

import com.videobridge.core.hls.MediaPlaylistPlanner
import com.videobridge.core.transcode.Fmp4Splitter
import com.videobridge.core.transcode.TranscodeTarget
import com.videobridge.core.transcode.VideoCodec
import com.videobridge.data.proxy.ClientTranscodeSource
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * One on-device client-transcode playback session: a seekable VOD HLS/CMAF origin backed by
 * [OnDeviceTranscoder]. The playlist advertises the whole runtime (via [MediaPlaylistPlanner]); each
 * segment is transcoded on demand when the TV requests it, so seeking anywhere just triggers a fresh
 * segment transcode (full seek). Transcoded fragments are split into one shared init segment + bare
 * media segments by [Fmp4Splitter]. A small LRU keeps recently-served media segments.
 *
 * Methods are blocking (called from the proxy's connection threads); transcoding is serialized so only
 * one HW encode runs at a time.
 */
class ClientTranscodeSession(
    private val transcoder: OnDeviceTranscoder,
    private val sourceUri: String,
    runtimeSeconds: Double,
    private val target: TranscodeTarget,
    private val cacheDir: File,
    private val logger: DiagnosticsLogger,
    /** Headers for the source request (e.g. Jellyfin Authorization for an online origin); phone-only. */
    private val sourceHeaders: Map<String, String>? = null,
) : ClientTranscodeSource {

    private val planner = MediaPlaylistPlanner()
    private val segments = planner.planSegments(runtimeSeconds)

    private val mediaCache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > MAX_CACHED
    }

    @Volatile private var initSeg: ByteArray? = null
    /** Track timescales from the init segment's moov, needed to place each segment's tfdt on the timeline. */
    @Volatile private var timescales: Map<Int, Long> = emptyMap()
    /** Codecs/resolution parsed from the init segment, for the master playlist's CODECS attribute. */
    @Volatile private var codecInfo: Fmp4Splitter.CodecInfo? = null
    /** Set once [release] is called so no new segment transcode starts during/after teardown. */
    @Volatile private var released = false

    init {
        logger.event(
            "transcode",
            "On-device transcode session: ${target.videoCodec} ${target.maxResolution.maxHeightPx}p, " +
                "${segments.size} segment(s) over ${runtimeSeconds.toLong()}s",
        )
    }

    override fun playlist(proxyBase: String): String {
        // MASTER playlist: a Cast/CMAF receiver needs a CODECS declaration to start an fMP4 stream, so
        // ensure the init segment exists (transcodes segment 0 if needed) to read the real codecs, then
        // advertise a single variant pointing at the media playlist.
        synchronized(this) { if (initSeg == null) transcodeInto(0) }
        val info = codecInfo
        val codecs = info?.hlsCodecs ?: fallbackCodecs()
        return planner.buildMasterPlaylist(
            mediaUri = "$proxyBase/stream?seg=media",
            codecs = codecs,
            width = info?.width,
            height = info?.height ?: target.maxResolution.maxHeightPx,
            bandwidthBps = estimatedBandwidthBps(),
        )
    }

    override fun mediaPlaylist(proxyBase: String): String =
        planner.buildVodPlaylist(
            segments,
            fmp4 = true,
            segmentUri = { "$proxyBase/stream?seg=$it" },
            initUri = { "$proxyBase/stream?seg=init" },
        )

    @Synchronized
    override fun initSegment(): ByteArray {
        if (initSeg == null) transcodeInto(0)
        return initSeg ?: ByteArray(0)
    }

    @Synchronized
    override fun mediaSegment(index: Int): ByteArray {
        mediaCache[index]?.let { return it }
        transcodeInto(index)
        return mediaCache[index] ?: ByteArray(0)
    }

    private fun transcodeInto(index: Int) {
        if (released) return // teardown in progress: don't start a fresh HW encode that would race the delete
        val segment = segments.getOrNull(index) ?: return
        val startMs = (segment.startSeconds * 1000).toLong()
        val endMs = ((segment.startSeconds + segment.durationSeconds) * 1000).toLong()
        val outFile = File(cacheDir.apply { mkdirs() }, "seg_$index.mp4")
        try {
            runBlocking { transcoder.transcodeSegment(sourceUri, startMs, endMs, target, outFile, sourceHeaders) }
            val bytes = outFile.readBytes()
            if (bytes.isEmpty()) {
                // The encoder ran but produced nothing — the TV would get an empty segment and stall. Kept
                // as an event (not trace) so a shared report shows the on-device transcode isn't producing.
                logger.event("transcode", "On-device segment $index produced 0 bytes (transcode not producing output)")
                return
            }
            val firstEver = initSeg == null
            if (firstEver) {
                val init = Fmp4Splitter.initSegment(bytes)
                initSeg = init
                timescales = Fmp4Splitter.trackTimescales(init)
                codecInfo = Fmp4Splitter.codecInfo(init)
            }
            // Each segment is an independent clip transcode, so its tfdt resets to ~0; rewrite it to this
            // segment's true offset (and set the mfhd sequence) so the renderer sees one continuous timeline
            // instead of every segment starting at t=0 (which stalls Cast and makes DLNA loop a few frames).
            val media = Fmp4Splitter.mediaSegment(bytes)
            Fmp4Splitter.applyMediaTiming(media, sequenceNumber = index + 1, startSeconds = segment.startSeconds, timescales = timescales)
            mediaCache[index] = media
            if (firstEver) {
                // Confirms the on-device pipeline actually produced playable output (visible in reports),
                // and surfaces the exact codecs the fMP4 declares — the CODECS a Cast receiver needs.
                logger.event(
                    "transcode",
                    "On-device transcode produced its first segment: ${bytes.size / 1024} KiB " +
                        "(${target.videoCodec} ${target.maxResolution.maxHeightPx}p); init ${codecInfo?.summary ?: "codecs unparsed"}",
                )
            }
        } catch (e: Exception) {
            // A cancellation is expected on stop (release cancels the in-flight export) — log it calmly so a
            // shared report doesn't show a scary failure for a normal teardown.
            if (released) logger.trace("transcode", "On-device segment $index transcode cancelled on stop")
            else logger.w("transcode", "On-device segment $index transcode failed", e)
        } finally {
            runCatching { outFile.delete() }
        }
    }

    override fun release() {
        // Signal first so no new transcode starts, then abort any in-flight export and wait for it to unwind
        // (the transcode runs under this monitor via the @Synchronized segment methods) BEFORE deleting the
        // cache dir — otherwise the HW muxer's output file is deleted mid-write (muxing error 7001).
        released = true
        runCatching { transcoder.cancelInFlight() }
        synchronized(this) { runCatching { cacheDir.deleteRecursively() } }
    }

    /** RFC 6381 CODECS to advertise when the init segment couldn't be parsed — a safe H.264 + AAC-LC guess. */
    private fun fallbackCodecs(): String = when (target.videoCodec) {
        VideoCodec.HEVC -> "hvc1.1.6.L120.90,mp4a.40.2"
        VideoCodec.AV1 -> "av01.0.08M.08,mp4a.40.2"
        VideoCodec.VP9 -> "vp09.00.10.08,mp4a.40.2"
        VideoCodec.H264 -> "avc1.4d401f,mp4a.40.2" // H.264 Main@3.1 — widely decodable
    }

    /** A rough BANDWIDTH for the single master-playlist variant, by target resolution tier. */
    private fun estimatedBandwidthBps(): Int = when {
        target.maxResolution.maxHeightPx >= 2160 -> 40_000_000
        target.maxResolution.maxHeightPx >= 1080 -> 8_000_000
        target.maxResolution.maxHeightPx >= 720 -> 4_000_000
        else -> 1_500_000
    }

    private companion object {
        const val MAX_CACHED = 8
    }
}
