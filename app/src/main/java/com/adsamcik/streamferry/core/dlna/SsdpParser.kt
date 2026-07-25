package com.adsamcik.streamferry.core.dlna

import com.adsamcik.streamferry.core.net.ServerUrlValidator

/**
 * Parses an SSDP search response / NOTIFY into its headers (§11 discovery). Pure text logic.
 *
 * Treated as untrusted input: header count and value lengths are bounded to resist resource
 * exhaustion from a hostile LAN device.
 */
data class SsdpMessage(
    val startLine: String,
    val headers: Map<String, String>,
) {
    val location: String? get() = headers["LOCATION"]
    val st: String? get() = headers["ST"] ?: headers["NT"]
    val usn: String? get() = headers["USN"]
    val server: String? get() = headers["SERVER"]

    /** True if this looks like a UPnP MediaRenderer advertisement. */
    fun isMediaRenderer(): Boolean {
        val t = (st ?: "") + (usn ?: "")
        return t.contains("MediaRenderer", ignoreCase = true)
    }
}

object SsdpParser {
    const val MAX_HEADERS = 64
    const val MAX_LINE_LEN = 2048
    const val MAX_MESSAGE_LEN = 16 * 1024

    fun parse(raw: String): SsdpMessage? {
        if (raw.length > MAX_MESSAGE_LEN) return null
        val lines = raw.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val start = lines.first()
        if (start.length > MAX_LINE_LEN) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (headers.size >= MAX_HEADERS) break
            if (line.length > MAX_LINE_LEN) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().uppercase()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty()) headers[key] = value
        }
        return SsdpMessage(start, headers)
    }

    /**
     * Validate that an advertised LOCATION URL is an http(s) URL pointing at a private/LAN host.
     * Rejects non-http schemes AND public/remote addresses, so a hostile LAN device cannot lure the
     * control point into fetching a device description from an arbitrary remote host (SSRF).
     */
    fun isAcceptableLocation(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        return try {
            val u = java.net.URI(location)
            val scheme = u.scheme?.lowercase()
            val host = u.host
            (scheme == "http" || scheme == "https") &&
                !host.isNullOrBlank() &&
                ServerUrlValidator.isPrivateHost(host)
        } catch (_: Exception) {
            false
        }
    }
}
