package com.videobridge.core.hls

/**
 * HLS playlist rewriting (§7).
 *
 * When Jellyfin returns HLS, the TV must NEVER see Jellyfin playlist/segment/subtitle URLs. The
 * phone proxies the playlist and rewrites every URI (segments, key, map, media playlists, subtitle
 * renditions) to phone proxy URLs scoped to the active session. Pure text transform, unit-testable.
 *
 * Bounded segment data is kept only in memory; playlists/segments are never written to disk.
 */
class HlsRewriter(
    /** Maps an upstream-relative or absolute URI to a phone proxy URL for [proxyBase]. */
    private val proxyBase: String, // e.g. http://10.0.0.5:54213/session/<id>
) {
    /**
     * Rewrite a playlist body. Every non-comment line (a URI) and every URI= attribute inside
     * EXT-X tags is replaced with a proxy URL carrying the original (opaque, server-side mapped)
     * locator. We never embed the real upstream URL in the output; instead we pass an index/token
     * that the session resolves server-side via [encodeUpstream].
     */
    fun rewrite(playlist: String, encodeUpstream: (String) -> String): String {
        val sb = StringBuilder(playlist.length + 64)
        playlist.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> sb.append(line)
                trimmed.startsWith("#") -> sb.append(rewriteTagUris(line, encodeUpstream))
                else -> sb.append(proxySegmentUrl(trimmed, encodeUpstream))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n') + if (playlist.endsWith("\n")) "\n" else ""
    }

    private val uriAttr = Regex("URI=\"([^\"]*)\"")

    private fun rewriteTagUris(line: String, encodeUpstream: (String) -> String): String =
        uriAttr.replace(line) { m ->
            "URI=\"${proxySegmentUrl(m.groupValues[1], encodeUpstream)}\""
        }

    private fun proxySegmentUrl(rawUri: String, encodeUpstream: (String) -> String): String {
        val opaque = encodeUpstream(rawUri)
        return "$proxyBase/stream?seg=$opaque"
    }

    companion object {
        /** Media MIME types that must be preserved when proxying HLS resources. */
        val MIME_BY_EXTENSION = mapOf(
            "m3u8" to "application/vnd.apple.mpegurl",
            "ts" to "video/mp2t",
            "mp4" to "video/mp4",
            "m4s" to "video/iso.segment",
            "vtt" to "text/vtt",
            "aac" to "audio/aac",
        )

        fun mimeForExtension(ext: String): String? = MIME_BY_EXTENSION[ext.lowercase()]
    }
}
