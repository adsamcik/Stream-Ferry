package com.adsamcik.streamferry.core.dlna

import com.adsamcik.streamferry.core.metadata.MetadataSanitizer

/**
 * DIDL-Lite metadata for AVTransport SetAVTransportURI (§11).
 *
 * The metadata MUST reference only the phone proxy URL and a correct protocolInfo/MIME. It must
 * never expose Jellyfin URLs/tokens. All user-derived text (title) is XML-escaped. Pure logic.
 */
object DidlLite {

    /**
     * @param proxyUrl phone proxy stream URL (the only URL the TV ever receives).
     * @param title display title (escaped).
     * @param mimeType resolved MIME type (e.g. "video/mp4").
     * @param byteSeekable whether the phone proxy can honour byte-range requests for this resource.
     * @param dlnaProfile optional DLNA.ORG profile (e.g. "MP4_H264_..."); omitted if null.
     * @param durationSecs optional duration for res@duration.
     */
    fun build(
        proxyUrl: String,
        title: String,
        mimeType: String,
        byteSeekable: Boolean = false,
        dlnaProfile: String? = null,
        durationSecs: Long? = null,
    ): String {
        val safeTitle = MetadataSanitizer.receiverTitle(title)
        val protocolInfo = buildString {
            append("http-get:*:").append(mimeType).append(':')
            if (dlnaProfile != null) {
                append("DLNA.ORG_PN=").append(escape(dlnaProfile)).append(';')
            }
            // DLNA.ORG_OP=01 advertises byte seeking. Live progressive transcodes have no known
            // byte length and are seeked by re-resolving server-side, so claiming it would induce
            // unsupported renderer Range requests.
            append("DLNA.ORG_OP=").append(if (byteSeekable) "01" else "00")
                .append(";DLNA.ORG_FLAGS=01700000000000000000000000000000")
        }
        val durationAttr = durationSecs?.let { " duration=\"${formatDuration(it)}\"" } ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(safeTitle)}</dc:title>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="$protocolInfo"$durationAttr>${escape(proxyUrl)}</res>""" +
            """</item></DIDL-Lite>"""
    }

    fun formatDuration(totalSecs: Long): String {
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    /** XML-escape for element/attribute text (defence against metadata injection). */
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code in 0x20..0xFFFF || c == '\t' || c == '\n' || c == '\r') append(c)
        }
    }
}
