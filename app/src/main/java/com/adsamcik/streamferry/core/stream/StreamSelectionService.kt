package com.adsamcik.streamferry.core.stream

/**
 * Stream selection (§9). For a **server** source (Jellyfin) the phone does NOT transcode — it asks the
 * server for a TV-compatible stream and proxies the bytes. (Local files have no server, so an
 * incompatible local file is transcoded **on-device** instead — see docs/ONDEVICE_TRANSCODE.md.) This
 * service maps known target capabilities + the media source +
 * user preferences to a [StreamDecision] describing what to request from Jellyfin's playback-info
 * endpoint (direct play / remux / audio transcode / full HLS transcode / subtitle burn-in).
 *
 * It NEVER constructs Jellyfin URLs. It only chooses parameters; the data.jellyfin layer turns the
 * decision into an official playback-info request. Pure logic, fully unit-testable.
 *
 * Compatibility is never inferred from file extension alone (§9).
 */

enum class Protocol { CAST, DLNA }

/**
 * Whether choosing an audio track requires the SERVER to transcode/remux. A non-default track can't be
 * honored on DIRECT PLAY through the phone proxy — the TV would just play the container's own default
 * track (the proxy can't switch an embedded audio stream on Cast/DLNA) — so the server must mux the
 * selected audio. Selecting the default track (or clearing the selection) needs no transcode. Pure logic.
 */
object AudioTrackSelection {
    fun requiresServerTranscode(selectedIndex: Int?, defaultIndex: Int?): Boolean =
        selectedIndex != null && selectedIndex != defaultIndex
}

enum class PlayMethod {
    /** Container + codecs already compatible: stream the original file bytes (range proxy). */
    DIRECT_PLAY,
    /** Compatible codecs, incompatible container: server remuxes without re-encoding video. */
    DIRECT_STREAM_REMUX,
    /** Video compatible, audio not: server transcodes audio only. */
    AUDIO_TRANSCODE,
    /** Neither compatible: server performs full HLS transcode. */
    HLS_TRANSCODE,
}

/** Codec/container capabilities advertised or learned for a target. All optional/uncertain. */
data class TargetCapabilities(
    val protocol: Protocol,
    val modelName: String? = null,
    val supportedContainers: Set<String> = emptySet(),
    val supportedVideoCodecs: Set<String> = emptySet(),
    val supportedAudioCodecs: Set<String> = emptySet(),
    val supportsHevc: Boolean = false,
    val supports10Bit: Boolean = false,
    val supportsHls: Boolean = true,
    /** External text subtitle formats the target can render directly (e.g. "vtt", "srt"). */
    val supportedExternalSubtitleFormats: Set<String> = emptySet(),
    /** True only after a real playback test confirmed it; otherwise treat features as unverified. */
    val verifiedByPlaybackTest: Boolean = false,
)

/** Relevant properties of a Jellyfin media source / streams (subset). */
data class MediaProfile(
    val container: String,
    val videoCodec: String,
    val videoProfile: String? = null,
    val videoLevel: Int? = null,
    val bitDepth: Int = 8,
    val isHdr: Boolean = false,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val videoBitrateBps: Long? = null,
    val audioCodec: String,
    val audioChannels: Int = 2,
    val subtitleFormat: String? = null,
    /** Display rotation reported by the source container, when known. */
    val rotationDegrees: Int? = null,
    /** Pixel aspect ratio (sample-aspect-ratio), when the container exposes one. */
    val pixelWidthHeightRatio: Float? = null,
    /** Nominal source frame rate, when known. It is metadata, not an encoder promise. */
    val frameRate: Float? = null,
)

/** User preferences (§9). */
data class StreamPreferences(
    val mode: Mode = Mode.PREFER_COMPATIBILITY,
    val forceTranscode: Boolean = false,
    val maxBitrateBps: Long? = null,
    val disableSubtitles: Boolean = false,
    val allowSubtitleBurnIn: Boolean = false,
    /**
     * First try to stream the **original** (direct play / light remux) using a broad codec profile,
     * falling back to a server transcode only if the TV (Cast or DLNA) can't decode it. When false,
     * always use the safe conservative transcode profile (no direct-play attempt / fallback delay).
     */
    val preferDirectPlay: Boolean = true,
    /**
     * Maximum video height (px) to stream: 2160 (4K) down to 480. Anything taller is transcoded down. At
     * 4K the app also passes HEVC 10-bit / HDR through and lifts the bitrate cap (see [allow4kHdr]).
     */
    val maxVideoHeight: Int = 2160,
    /**
     * Auto-skip intro/outro/recap segments (Jellyfin Media Segments API). When true the engine seeks past
     * a segment the moment playback enters it (once per segment); the manual "Skip" button is unaffected.
     */
    val autoSkipSegments: Boolean = false,
    /**
     * Preferred audio language (ISO code) to auto-select when the media has a matching track, or null for
     * none (keep the server default). Already resolved per-show-memory-over-global by the caller.
     */
    val preferredAudioLanguage: String? = null,
    /**
     * Preferred subtitle language (ISO code) to auto-enable (burned in) when the media has a matching
     * track, or null for none/off. Already resolved per-show-memory-over-global by the caller.
     */
    val preferredSubtitleLanguage: String? = null,
) {
    /**
     * Attempt 4K / HDR (HEVC 10-bit) passthrough — advertise HEVC + 10-bit for direct play (Cast and
     * DLNA) and don't cap the bitrate — which applies only when the max resolution allows 4K. Falls back
     * to the safe H.264 transcode if the target can't decode it (H.264 can't carry HDR).
     */
    val allow4kHdr: Boolean get() = maxVideoHeight >= 2160

    enum class Mode { PREFER_COMPATIBILITY, PREFER_QUALITY }
}

data class StreamDecision(
    val playMethod: PlayMethod,
    val burnInSubtitles: Boolean,
    val maxBitrateBps: Long?,
    /** True when the resulting stream is HLS and the proxy must rewrite playlists/segments. */
    val producesHls: Boolean,
    val rationale: String,
)

class StreamSelectionService {

    fun select(
        caps: TargetCapabilities,
        media: MediaProfile,
        prefs: StreamPreferences,
    ): StreamDecision {
        val maxBitrate = prefs.maxBitrateBps

        // Subtitle burn-in is a server-side transcode. PGS/ASS/SSA are likely burn-in (§10).
        val sub = media.subtitleFormat?.lowercase()
        val subtitleNeedsBurnIn = !prefs.disableSubtitles && sub != null && when (sub) {
            "pgs", "pgssub", "dvdsub", "dvbsub" -> true // bitmap subs always burn-in
            "ass", "ssa" -> prefs.allowSubtitleBurnIn   // complex text: burn-in if user allows
            else -> false                                // srt/vtt: try as external text track
        }

        if (prefs.forceTranscode) {
            return StreamDecision(
                playMethod = PlayMethod.HLS_TRANSCODE,
                burnInSubtitles = subtitleNeedsBurnIn || (sub != null && !prefs.disableSubtitles && (sub == "ass" || sub == "ssa")),
                maxBitrateBps = maxBitrate,
                producesHls = caps.supportsHls,
                rationale = "User forced transcoding.",
            )
        }

        val videoOk = isVideoCompatible(caps, media)
        val audioOk = isAudioCompatible(caps, media)
        val containerOk = media.container.lowercase() in caps.supportedContainers
        val bitrateOk = maxBitrate == null || media.videoBitrateBps == null || media.videoBitrateBps <= maxBitrate

        // Burn-in forces a transcode regardless of codec compatibility.
        if (subtitleNeedsBurnIn) {
            return StreamDecision(
                PlayMethod.HLS_TRANSCODE, true, maxBitrate, caps.supportsHls,
                "Subtitle format '${sub}' requires server-side burn-in.",
            )
        }

        return when {
            prefs.mode == StreamPreferences.Mode.PREFER_QUALITY && videoOk && audioOk && containerOk && bitrateOk ->
                StreamDecision(PlayMethod.DIRECT_PLAY, false, maxBitrate, false,
                    "Quality mode: original is target-compatible; direct play.")

            videoOk && audioOk && containerOk && bitrateOk ->
                StreamDecision(PlayMethod.DIRECT_PLAY, false, maxBitrate, false,
                    "Container + video + audio compatible; direct play (range proxy).")

            videoOk && audioOk && bitrateOk ->
                StreamDecision(PlayMethod.DIRECT_STREAM_REMUX, false, maxBitrate, false,
                    "Codecs compatible, container '${media.container}' not; request remux/direct-stream.")

            videoOk && bitrateOk ->
                StreamDecision(PlayMethod.AUDIO_TRANSCODE, false, maxBitrate, false,
                    "Video compatible, audio '${media.audioCodec}' not; request audio transcode.")

            else ->
                StreamDecision(PlayMethod.HLS_TRANSCODE, false, maxBitrate, caps.supportsHls,
                    "No compatible direct path; request HLS transcode.")
        }
    }

    private fun isVideoCompatible(caps: TargetCapabilities, media: MediaProfile): Boolean {
        val codec = media.videoCodec.lowercase()
        if (codec !in caps.supportedVideoCodecs) return false
        if (codec in setOf("hevc", "h265") && !caps.supportsHevc) return false
        if ((media.bitDepth > 8 || media.isHdr) && !caps.supports10Bit) return false
        return true
    }

    private fun isAudioCompatible(caps: TargetCapabilities, media: MediaProfile): Boolean =
        media.audioCodec.lowercase() in caps.supportedAudioCodecs
}
