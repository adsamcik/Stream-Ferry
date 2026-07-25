package com.adsamcik.streamferry.core.transcode

/**
 * Quality band relative to the source, independent of codec/engine (§9). Bands are tried best-first so we
 * always deliver the highest quality the TV can actually play. NATIVE is the untouched original (direct
 * play only); the transcode bands trade resolution/bit-depth/bitrate down step by step. STANDARD and LOW
 * are pinned to H.264 as the universally-decodable safety floor.
 */
enum class QualityBand(val label: String) {
    NATIVE("Native"),
    MAX_TRANSCODE("Max quality"),
    VERY_HIGH("Very high"),
    HIGH("High"),
    STANDARD("Standard"),
    LOW("Low"),
}

/** Where a stream is produced. Priority order for a given band: DIRECT > SERVER > CLIENT_HW > CLIENT_CPU. */
enum class PlaybackEngineKind(val label: String) {
    DIRECT("Direct"),
    SERVER("Server transcode"),
    CLIENT_HW("On-device (hardware)"),
    CLIENT_CPU("On-device (CPU)"),
}

/** The source's relevant properties for planning. */
data class PlannerSource(
    val codec: VideoCodec?,
    val tier: ResolutionTier,
    val tenBit: Boolean,
    val bitrateBps: Long?,
    /** True if the TV can decode the ORIGINAL as-is (direct play possible). */
    val canDirectPlay: Boolean,
)

/** Which transcode engines are usable this session (from prefs + whether the source has a server). */
data class EngineAvailability(
    val server: Boolean,
    val clientHardware: Boolean,
    val clientCpu: Boolean,
)

/** One concrete, ordered playback attempt the engine will try (and fall through on failure/stall). */
data class PlaybackAttempt(
    val band: QualityBand,
    val engine: PlaybackEngineKind,
    val codec: VideoCodec,
    val tier: ResolutionTier,
    val tenBit: Boolean,
    /** Target bitrate cap for a transcode; null for a NATIVE/direct attempt. */
    val maxBitrateBps: Long?,
    /** Client-transcode packaging; null for DIRECT/SERVER (the server picks its own container). */
    val container: StreamContainer?,
    val rationale: String,
) {
    val isTranscode: Boolean get() = engine != PlaybackEngineKind.DIRECT
    val isOnDevice: Boolean get() = engine == PlaybackEngineKind.CLIENT_HW || engine == PlaybackEngineKind.CLIENT_CPU
}

/**
 * Pure playback planner (§9). Produces an **ordered** list of [PlaybackAttempt]s that maximises viewing
 * quality: pick the best quality [QualityBand] the TV can play, preferring direct play, and satisfy it
 * with the best available engine (server > on-device HW > on-device CPU) and the most efficient codec the
 * TV supports. The engine walks the list, advancing to the next attempt when one fails or can't keep up.
 *
 * Guarantees a universally-decodable H.264 floor (the STANDARD/LOW bands are H.264-only), mirroring the
 * server-side DeviceProfiles fallback. Framework-free and fully unit-tested.
 */
class PlaybackPlanner {

    fun plan(
        source: PlannerSource,
        receiver: ReceiverPlaybackCapabilities,
        device: DeviceEncodeCapabilities,
        engines: EngineAvailability,
        preferDirect: Boolean = true,
    ): List<PlaybackAttempt> {
        val attempts = mutableListOf<PlaybackAttempt>()

        // 1. NATIVE via DIRECT — the best experience when the TV can decode the original itself.
        if (preferDirect && source.canDirectPlay) {
            attempts += PlaybackAttempt(
                band = QualityBand.NATIVE,
                engine = PlaybackEngineKind.DIRECT,
                codec = source.codec ?: VideoCodec.H264,
                tier = source.tier,
                tenBit = source.tenBit,
                maxBitrateBps = null,
                container = null,
                rationale = "TV can play the original; direct play (no transcode).",
            )
        }

        // 2. Transcode bands, best quality first; within each, engine priority SERVER > CLIENT_HW > CLIENT_CPU.
        for (band in TRANSCODE_BANDS) {
            val spec = bandSpec(band, source, receiver)
            for (engine in TRANSCODE_ENGINES) {
                if (!engines.isAvailable(engine)) continue
                attempts += bestAttempt(band, spec, engine, receiver, device) ?: continue
            }
        }

        // Collapse attempts that resolve to the identical stream (bands coincide for low-res sources).
        return attempts.distinctBy { listOf(it.engine, it.codec, it.tier, it.tenBit, it.maxBitrateBps) }
    }

    private data class BandSpec(
        val tier: ResolutionTier,
        val tenBit: Boolean,
        val bitrateBps: Long,
        /** MAX/VERY_HIGH/HIGH pick the best codec; STANDARD/LOW are pinned to H.264 (the safety floor). */
        val preferBestCodec: Boolean,
    )

    private fun bandSpec(band: QualityBand, source: PlannerSource, receiver: ReceiverPlaybackCapabilities): BandSpec {
        val keepDepth = source.tenBit && receiver.tenBit
        val srcBitrate = source.bitrateBps?.takeIf { it > 0 } ?: defaultBitrate(source.tier)
        fun ladder(tier: ResolutionTier) = defaultBitrate(tier).coerceAtMost(srcBitrate)
        return when (band) {
            QualityBand.MAX_TRANSCODE ->
                BandSpec(source.tier, keepDepth, srcBitrate.coerceAtMost(MAX_BITRATE_BPS), preferBestCodec = true)
            QualityBand.VERY_HIGH ->
                BandSpec(source.tier, keepDepth, (srcBitrate * 6 / 10).coerceAtLeast(MIN_BITRATE_BPS), preferBestCodec = true)
            QualityBand.HIGH -> minTier(source.tier, ResolutionTier.FHD_1080P).let { BandSpec(it, false, ladder(it), preferBestCodec = true) }
            QualityBand.STANDARD -> minTier(source.tier, ResolutionTier.HD_720P).let { BandSpec(it, false, ladder(it), preferBestCodec = false) }
            QualityBand.LOW -> minTier(source.tier, ResolutionTier.SD_480P).let { BandSpec(it, false, ladder(it), preferBestCodec = false) }
            QualityBand.NATIVE -> BandSpec(source.tier, keepDepth, srcBitrate, preferBestCodec = true)
        }
    }

    /** Best (codec, container) for a band+engine, or null if the engine can't satisfy it for this TV. */
    private fun bestAttempt(
        band: QualityBand,
        spec: BandSpec,
        engine: PlaybackEngineKind,
        receiver: ReceiverPlaybackCapabilities,
        device: DeviceEncodeCapabilities,
    ): PlaybackAttempt? {
        val codecOrder = if (spec.preferBestCodec) VideoCodec.entries else listOf(VideoCodec.H264)
        for (codec in codecOrder) {
            if (!receiver.canPlay(codec, spec.tier)) continue
            if (!engineCanProduce(engine, codec, spec.tier, device)) continue
            val container = clientContainer(codec, receiver)
            if (engine != PlaybackEngineKind.SERVER && container == null) continue // client needs a package
            val tenBit = spec.tenBit && codec != VideoCodec.H264
            return PlaybackAttempt(
                band = band,
                engine = engine,
                codec = codec,
                tier = spec.tier,
                tenBit = tenBit,
                maxBitrateBps = spec.bitrateBps,
                container = if (engine == PlaybackEngineKind.SERVER) null else container,
                rationale = "${band.label} via ${engine.label}: $codec ${spec.tier.maxHeightPx}p" +
                    (if (tenBit) " 10-bit" else "") + " @ ${spec.bitrateBps / 1_000_000}Mbps.",
            )
        }
        return null
    }

    private fun engineCanProduce(engine: PlaybackEngineKind, codec: VideoCodec, tier: ResolutionTier, device: DeviceEncodeCapabilities): Boolean =
        when (engine) {
            // The server attempts any TV-playable codec; if its FFmpeg lacks it the attempt fails and the
            // engine falls through to the next one — so we optimistically allow all here.
            PlaybackEngineKind.SERVER -> true
            PlaybackEngineKind.CLIENT_HW -> device.canEncodeHardware(codec, tier)
            PlaybackEngineKind.CLIENT_CPU -> device.canEncodeSoftware(codec, tier)
            PlaybackEngineKind.DIRECT -> false
        }

    private fun clientContainer(codec: VideoCodec, receiver: ReceiverPlaybackCapabilities): StreamContainer? =
        when (codec) {
            VideoCodec.H264 -> when {
                receiver.supportsTs -> StreamContainer.HLS_TS
                receiver.supportsFmp4 -> StreamContainer.HLS_FMP4
                else -> null
            }
            else -> when {
                receiver.supportsFmp4 -> StreamContainer.HLS_FMP4
                receiver.supportsDash -> StreamContainer.DASH_FMP4
                else -> null
            }
        }

    private fun EngineAvailability.isAvailable(engine: PlaybackEngineKind) = when (engine) {
        PlaybackEngineKind.SERVER -> server
        PlaybackEngineKind.CLIENT_HW -> clientHardware
        PlaybackEngineKind.CLIENT_CPU -> clientCpu
        PlaybackEngineKind.DIRECT -> true
    }

    private fun minTier(a: ResolutionTier, b: ResolutionTier): ResolutionTier =
        if (a.maxHeightPx <= b.maxHeightPx) a else b

    private fun defaultBitrate(tier: ResolutionTier): Long = when (tier) {
        ResolutionTier.UHD_4K -> 40_000_000L
        ResolutionTier.FHD_1080P -> 8_000_000L
        ResolutionTier.HD_720P -> 4_000_000L
        ResolutionTier.SD_480P -> 1_500_000L
    }

    companion object {
        private val TRANSCODE_BANDS = listOf(
            QualityBand.MAX_TRANSCODE, QualityBand.VERY_HIGH, QualityBand.HIGH, QualityBand.STANDARD, QualityBand.LOW,
        )
        private val TRANSCODE_ENGINES = listOf(
            PlaybackEngineKind.SERVER, PlaybackEngineKind.CLIENT_HW, PlaybackEngineKind.CLIENT_CPU,
        )
        private const val MAX_BITRATE_BPS = 120_000_000L
        private const val MIN_BITRATE_BPS = 600_000L
    }
}
