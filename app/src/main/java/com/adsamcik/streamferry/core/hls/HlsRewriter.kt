package com.adsamcik.streamferry.core.hls

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
    /** Bounds expansion while URI lines and attributes are replaced with longer proxy URLs. */
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) {
    init {
        require(maxOutputChars >= 1) { "maxOutputChars must be >= 1" }
    }

    /**
     * Rewrite a playlist body. Every non-comment line (a URI) and every URI= attribute inside
     * EXT-X tags is replaced with a proxy URL carrying the original (opaque, server-side mapped)
     * locator. We never embed the real upstream URL in the output; instead we pass an index/token
     * that the session resolves server-side via [encodeUpstream].
     */
    fun rewrite(playlist: String, encodeUpstream: (String) -> String): String {
        val output = BoundedOutput(maxOutputChars, playlist.length.coerceAtMost(maxOutputChars))
        playlist.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> output.append(line)
                trimmed.startsWith("#") -> appendRewrittenTagUris(output, line, encodeUpstream)
                else -> output.append(proxySegmentUrl(trimmed, encodeUpstream))
            }
            output.append('\n')
        }
        output.trimEnd('\n')
        if (playlist.endsWith("\n")) output.append('\n')
        return output.toString()
    }

    private val uriAttr = Regex("URI=\"([^\"]*)\"")

    private fun appendRewrittenTagUris(
        output: BoundedOutput,
        line: String,
        encodeUpstream: (String) -> String,
    ) {
        var cursor = 0
        uriAttr.findAll(line).forEach { match ->
            output.append(line, cursor, match.range.first)
            output.append("URI=\"")
            output.append(proxySegmentUrl(match.groupValues[1], encodeUpstream))
            output.append('\"')
            cursor = match.range.last + 1
        }
        output.append(line, cursor, line.length)
    }

    private fun proxySegmentUrl(rawUri: String, encodeUpstream: (String) -> String): String {
        val opaque = encodeUpstream(rawUri)
        return "$proxyBase/stream?seg=$opaque"
    }

    private class BoundedOutput(maxChars: Int, initialCapacity: Int) {
        private val maxChars = maxChars
        private val builder = StringBuilder(initialCapacity)

        fun append(value: Char) {
            ensureCapacity(1)
            builder.append(value)
        }

        fun append(value: String) {
            ensureCapacity(value.length)
            builder.append(value)
        }

        fun append(value: String, startIndex: Int, endIndex: Int) {
            ensureCapacity(endIndex - startIndex)
            builder.append(value, startIndex, endIndex)
        }

        fun trimEnd(value: Char) {
            while (builder.isNotEmpty() && builder.last() == value) {
                builder.setLength(builder.length - 1)
            }
        }

        override fun toString(): String = builder.toString()

        private fun ensureCapacity(additionalChars: Int) {
            if (additionalChars > maxChars - builder.length) {
                throw IllegalArgumentException("Rewritten HLS playlist exceeds the output limit")
            }
        }
    }

    companion object {
        /** Limits transformed playlists before the final UTF-8 response allocation. */
        const val DEFAULT_MAX_OUTPUT_CHARS = 8 * 1024 * 1024

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
