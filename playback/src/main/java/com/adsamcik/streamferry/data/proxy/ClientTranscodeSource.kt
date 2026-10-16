package com.adsamcik.streamferry.data.proxy

import java.io.InputStream

/**
 * A phone-hosted, on-device-transcoded HLS/CMAF origin for one local-file playback session. The proxy
 * serves the playlist + segments produced here; the TV only ever sees the proxy URL. Methods are blocking
 * (called from the proxy's connection threads) and may transcode a segment on demand.
 */
interface ClientTranscodeSource {
    /** DLNA consumes a single continuous transport stream; Cast consumes the HLS resources below. */
    val isProgressive: Boolean get() = false
    val segmentContentType: String get() = "video/mp4"

    /** Open one continuous receiver read. HEAD must never call this method. */
    fun openProgressive(): InputStream? = null

    /**
     * The MASTER (multivariant) HLS playlist: declares the stream's `CODECS` (so a Cast/CMAF receiver
     * starts the fMP4 stream) and points at the media playlist.
     *
     * This method must be metadata-only: a receiver commonly probes a playlist with `HEAD`, and a
     * probe must not start a hardware export just to discover the eventual init segment.
     */
    fun playlist(proxyBase: String, allowExport: Boolean): String

    /** The VOD HLS MEDIA playlist (advertises the whole runtime so the TV can seek anywhere). */
    fun mediaPlaylist(proxyBase: String): String

    /** Compatibility alias for the first CMAF init segment. */
    fun initSegment(allowExport: Boolean): Resource

    /** Each independent clip has its own `EXT-X-MAP`. HTTP `HEAD` never starts an export. */
    fun initSegment(index: Int, allowExport: Boolean): Resource =
        if (index == 0) initSegment(allowExport) else Resource.NotFound

    /** The bare CMAF media segment for [index], transcoded on demand (full seek). */
    fun mediaSegment(index: Int, allowExport: Boolean): Resource

    /** Release transcoder/cache resources when the session ends. */
    fun release()

    /**
     * A deterministic transcode resource result. In particular, a failed or unavailable export is
     * never represented as an empty byte array: doing so used to make the proxy return `200 OK` with
     * a zero-byte fMP4 segment, which receivers treat as a mysterious playback stall.
     */
    sealed interface Resource {
        data class Ready(val bytes: ByteArray) : Resource
        data object NotFound : Resource
        data object Unavailable : Resource
        data object TimedOut : Resource
    }
}
