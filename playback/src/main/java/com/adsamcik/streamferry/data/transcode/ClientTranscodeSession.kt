package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.hls.MediaPlaylistPlanner
import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * One on-device client-transcode playback session: a seekable VOD HLS/CMAF origin backed by
 * [OnDeviceTranscoder]. The playlist advertises the whole runtime (via [MediaPlaylistPlanner]); each
 * segment is transcoded on demand when the TV requests it, so seeking anywhere just triggers a fresh
 * segment transcode (full seek). Each independent export retains its timestamps and edit lists, with
 * its own init segment and an HLS discontinuity. A byte-bounded LRU keeps matching init/media pairs.
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
    private val logger: DiagnosticSink,
) : ClientTranscodeSource {

    private val planner = MediaPlaylistPlanner()
    private val segments = planner.planSegments(runtimeSeconds)

    /** A byte-bounded LRU. A count cap alone allowed a handful of 4K segments to consume tens of MiB. */
    private data class CachedClip(val init: ByteArray, val media: ByteArray) {
        val byteSize: Long get() = init.size.toLong() + media.size.toLong()
    }
    private val mediaCache = LinkedHashMap<Int, CachedClip>(16, 0.75f, true)
    private var mediaCacheBytes = 0L
    private var observedPeakBandwidthBps: Long = 0L

    /** Codecs/resolution parsed from the init segment, for the master playlist's CODECS attribute. */
    @Volatile private var codecInfo: Fmp4Splitter.CodecInfo? = null
    /** Set once [release] is called so no new segment transcode starts during/after teardown. */
    @Volatile private var released = false
    /** Stable identity used to drain only this session's Media3 reservation during cleanup. */
    private val exportOwner = Any()

    init {
        require(target.container == StreamContainer.HLS_FMP4) {
            "the on-device pipeline emits only HLS fragmented MP4"
        }
        require(target.videoCodec == VideoCodec.H264 || target.videoCodec == VideoCodec.HEVC) {
            "the on-device Transformer path supports only H.264 and HEVC targets"
        }
        require(!target.tenBit) {
            "the on-device Transformer path does not yet enforce a verifiable Main10 output contract"
        }
        logger.event(
            "transcode",
            "On-device transcode session: ${target.videoCodec} ${target.maxResolution.maxHeightPx}p, " +
                "${segments.size} segment(s) over ${runtimeSeconds.toLong()}s",
        )
    }

    @Synchronized
    override fun playlist(proxyBase: String, allowExport: Boolean): String {
        // A GET may prepare segment zero to derive the actual init metadata. A HEAD probe must be cheap
        // and side-effect free, so it receives the target fallback until a normal GET has published init.
        if (allowExport && codecInfo == null && !released) {
            transcodeInto(0)
        }
        val info = codecInfo
        return planner.buildMasterPlaylist(
            mediaUri = "$proxyBase/stream?seg=media",
            codecs = info?.hlsCodecs ?: fallbackCodecs(),
            width = info?.width,
            height = info?.height ?: target.maxResolution.maxHeightPx,
            bandwidthBps = declaredBandwidthBps(),
        )
    }

    @Synchronized
    override fun mediaPlaylist(proxyBase: String): String =
        planner.buildVodPlaylist(
            // Source clipping follows this stable cadence, including out-of-order seeks. Discontinuities
            // permit each export's own sample-aligned timestamps and encoder priming/edit lists.
            segments,
            fmp4 = true,
            segmentUri = { "$proxyBase/stream?seg=$it" },
            segmentInitUri = { "$proxyBase/stream?seg=init_$it" },
            discontinuityBetweenSegments = true,
        )

    @Synchronized
    override fun initSegment(allowExport: Boolean): ClientTranscodeSource.Resource = initSegment(0, allowExport)

    @Synchronized
    override fun initSegment(index: Int, allowExport: Boolean): ClientTranscodeSource.Resource {
        if (released) return ClientTranscodeSource.Resource.Unavailable
        if (segments.getOrNull(index) == null) return ClientTranscodeSource.Resource.NotFound
        mediaCache[index]?.let { return ClientTranscodeSource.Resource.Ready(it.init) }
        if (!allowExport) return ClientTranscodeSource.Resource.Unavailable
        return when (val result = transcodeInto(index)) {
            is ClientTranscodeSource.Resource.Ready ->
                mediaCache[index]?.let { ClientTranscodeSource.Resource.Ready(it.init) } ?: unavailable()
            else -> result
        }
    }

    @Synchronized
    override fun mediaSegment(index: Int, allowExport: Boolean): ClientTranscodeSource.Resource {
        if (released) return ClientTranscodeSource.Resource.Unavailable
        if (segments.getOrNull(index) == null) return ClientTranscodeSource.Resource.NotFound
        mediaCache[index]?.let { return ClientTranscodeSource.Resource.Ready(it.media) }
        if (!allowExport) return ClientTranscodeSource.Resource.Unavailable
        return transcodeInto(index)
    }

    /**
     * Export, structurally validate, and then publish exactly one media segment. This runs under the
     * session monitor, coalescing duplicate renderer requests without publishing a partial file.
     */
    private fun transcodeInto(index: Int): ClientTranscodeSource.Resource {
        if (released) return ClientTranscodeSource.Resource.Unavailable
        val segment = segments.getOrNull(index) ?: return ClientTranscodeSource.Resource.NotFound
        mediaCache[index]?.let { return ClientTranscodeSource.Resource.Ready(it.media) }

        val startMs = (segment.startSeconds * 1_000).toLong()
        val endMs = ((segment.startSeconds + segment.durationSeconds) * 1_000).toLong()
        if (endMs <= startMs) return unavailable()
        val workDir = cacheDir.apply { mkdirs() }
        if (!workDir.isDirectory || workDir.usableSpace < MIN_FREE_WORKING_SPACE_BYTES) {
            logger.event("transcode", "On-device segment $index declined: insufficient private cache space")
            return unavailable()
        }
        // The muxer writes only to this private working path. Nothing is served until it has completed
        // and passed the fMP4 checks below.
        val outFile = File(workDir, "seg_$index.working.mp4")
        var canDeleteWorkingFile = false
        try {
            runBlocking {
                transcoder.transcodeSegment(
                    sourceUri,
                    startMs,
                    endMs,
                    target,
                    outFile,
                    owner = exportOwner,
                    abortIf = { released },
                )
            }
            // Media3 reported terminal completion, so it is safe to remove this file on a validation error.
            canDeleteWorkingFile = true
            val size = outFile.length()
            if (!outFile.isFile || size <= 0L || size > MAX_EXPORT_FILE_BYTES) {
                logger.event("transcode", "On-device segment $index was not a publishable size ($size B)")
                return unavailable()
            }
            val bytes = outFile.readBytes()
            val init = Fmp4Splitter.initSegment(bytes)
            val media = Fmp4Splitter.mediaSegment(bytes)
            val fingerprint = Fmp4Splitter.initFingerprint(init)
            if (!Fmp4Splitter.isValidInitSegment(init) ||
                !Fmp4Splitter.isValidMediaSegment(media) ||
                fingerprint == null ||
                !Fmp4Splitter.hasExpectedFragmentTracks(media, fingerprint.tracks.map { it.trackId }.toSet())
            ) {
                logger.event("transcode", "On-device segment $index failed fMP4 publication validation")
                return unavailable()
            }

            if (init.size > MAX_INIT_BYTES || init.size.toLong() + media.size.toLong() > MAX_MEDIA_CACHE_BYTES) {
                // Init and media must remain paired. Serving an uncached init then regenerating its
                // media could use a different sample description or encoder edit list.
                logger.event("transcode", "On-device segment $index exceeds the paired init/media cache budget")
                return unavailable()
            }
            val candidateTimescales = Fmp4Splitter.trackTimescales(init)
            if (candidateTimescales.keys != fingerprint.tracks.map { it.trackId }.toSet()) {
                logger.w("transcode", "On-device segment $index init has incomplete or duplicate track timing")
                return unavailable()
            }
            val candidateCodecInfo = Fmp4Splitter.codecInfo(init)
            val videoCodec = candidateCodecInfo?.videoCodec?.substringBefore('.')
            val expectedCodecs = if (target.videoCodec == VideoCodec.H264) setOf("avc1", "avc3") else setOf("hvc1", "hev1")
            if (videoCodec !in expectedCodecs) {
                logger.w("transcode", "On-device segment $index did not produce requested ${target.videoCodec}; refusing it")
                return unavailable()
            }
            if (!Fmp4Splitter.hasBoundedIndependentTrackTimelines(
                    media,
                    candidateTimescales,
                    durationSeconds = segment.durationSeconds,
                )
            ) {
                logger.w(
                    "transcode",
                    "On-device segment $index exceeds sample-sized bounds around its ${segment.durationSeconds}s clip " +
                        "(measured ${Fmp4Splitter.mediaDurationSeconds(media, candidateTimescales)}s); refusing it",
                )
                return unavailable()
            }
            val bandwidth = ((media.size.toDouble() * 8.0) /
                segment.durationSeconds.coerceAtLeast(MIN_DURATION_FOR_RATE_SECONDS)).toLong()
            val firstEver = codecInfo == null
            if (firstEver) codecInfo = candidateCodecInfo
            observedPeakBandwidthBps = maxOf(observedPeakBandwidthBps, bandwidth)
            cacheMedia(index, CachedClip(init, media))

            if (firstEver) {
                logger.event(
                    "transcode",
                    "On-device transcode produced its first segment: ${bytes.size / 1024} KiB " +
                        "(${target.videoCodec} ${target.maxResolution.maxHeightPx}p); init ${candidateCodecInfo?.summary ?: "codecs unparsed"}",
                )
            }
            return ClientTranscodeSource.Resource.Ready(media)
        } catch (e: OnDeviceTranscoder.ExportDeadlineExceededException) {
            canDeleteWorkingFile = e.stopped
            if (!released) logger.w("transcode", "On-device segment $index exceeded its export deadline", e)
            return timedOut()
        } catch (e: Exception) {
            // A cancellation is expected on stop; all other failures become a deterministic 503, never an
            // empty `200 OK` media response that leaves the renderer buffering forever. Only remove the
            // working file after this session's encoder reservation confirms it has stopped touching it.
            canDeleteWorkingFile = transcoder.cancelOwnedInFlightAndAwait(exportOwner)
            if (released) logger.trace("transcode", "On-device segment $index cancelled on stop")
            else logger.w("transcode", "On-device segment $index transcode failed", e)
            return unavailable()
        } finally {
            if (canDeleteWorkingFile) {
                runCatching { outFile.delete() }
            } else {
                logger.w("transcode", "Retaining working file for segment $index until a still-running export stops")
            }
        }
    }

    private fun cacheMedia(index: Int, clip: CachedClip) {
        mediaCache.remove(index)?.let { mediaCacheBytes -= it.byteSize }
        mediaCache[index] = clip
        mediaCacheBytes += clip.byteSize
        val iterator = mediaCache.entries.iterator()
        while (mediaCacheBytes > MAX_MEDIA_CACHE_BYTES && iterator.hasNext()) {
            val evicted = iterator.next()
            mediaCacheBytes -= evicted.value.byteSize
            iterator.remove()
        }
    }

    private fun unavailable(): ClientTranscodeSource.Resource =
        ClientTranscodeSource.Resource.Unavailable

    private fun timedOut(): ClientTranscodeSource.Resource =
        ClientTranscodeSource.Resource.TimedOut

    override fun release() {
        // Signal first and delete the directory only after Media3 has stopped. If the bounded drain fails,
        // retaining app-cache files is safer than deleting a path the muxer may still be writing into.
        released = true
        val stopped = transcoder.cancelOwnedInFlightAndAwait(exportOwner)
        synchronized(this) {
            mediaCache.clear()
            mediaCacheBytes = 0L
            codecInfo = null
            if (stopped) {
                runCatching { cacheDir.deleteRecursively() }
            } else {
                logger.w("transcode", "Retaining session cache after cancel-drain timeout; app cache cleanup will reclaim it safely")
            }
        }
    }

    /** RFC 6381 fallback used only for HEAD probes before an actual init segment is available. */
    private fun fallbackCodecs(): String = when (target.videoCodec) {
        VideoCodec.HEVC -> "hvc1"
        VideoCodec.H264 -> "avc1"
        VideoCodec.AV1, VideoCodec.VP9 -> error("unsupported by the on-device fMP4 pipeline")
    }

    /**
     * The master is generated after segment zero on GET, so this is normally measured. A conservative
     * provisional ceiling exists solely for a HEAD probe, which must not trigger an export.
     */
    private fun declaredBandwidthBps(): Int =
        maxOf(observedPeakBandwidthBps, PROVISIONAL_BANDWIDTH_BPS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val MAX_MEDIA_CACHE_BYTES = 24L * 1024L * 1024L
        /** Reject an oversized completed export before reading it wholesale into memory. */
        const val MAX_EXPORT_FILE_BYTES = 64L * 1024L * 1024L
        /** Preflight headroom for Media3's unbounded temporary export plus app-cache bookkeeping. */
        const val MIN_FREE_WORKING_SPACE_BYTES = 128L * 1024L * 1024L
        const val MAX_INIT_BYTES = 4 * 1024 * 1024
        const val PROVISIONAL_BANDWIDTH_BPS = 100_000_000L
        const val MIN_DURATION_FOR_RATE_SECONDS = 0.001
    }
}
