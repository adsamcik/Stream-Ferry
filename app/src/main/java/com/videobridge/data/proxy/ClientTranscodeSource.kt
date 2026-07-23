package com.videobridge.data.proxy

/**
 * A phone-hosted, on-device-transcoded HLS/CMAF origin for one playback session. The proxy serves the
 * playlist + segments produced here; the TV only ever sees the proxy URL. Methods are blocking (called
 * from the proxy's connection threads) and may transcode a segment on demand.
 */
interface ClientTranscodeSource {
    /**
     * The MASTER (multivariant) HLS playlist: declares the stream's `CODECS` (so a Cast/CMAF receiver
     * starts the fMP4 stream) and points at the media playlist. May transcode the init segment on first
     * call to learn the codecs, so it can block briefly.
     */
    fun playlist(proxyBase: String): String

    /** The VOD HLS MEDIA playlist (advertises the whole runtime so the TV can seek anywhere). */
    fun mediaPlaylist(proxyBase: String): String

    /** The shared CMAF init segment (`EXT-X-MAP`). */
    fun initSegment(): ByteArray

    /** The bare CMAF media segment for [index], transcoded on demand (full seek). */
    fun mediaSegment(index: Int): ByteArray

    /** Release transcoder/cache resources when the session ends. */
    fun release()
}
