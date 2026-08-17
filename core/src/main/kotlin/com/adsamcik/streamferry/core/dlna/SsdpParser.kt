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

    /** True only for a successful HTTP-style M-SEARCH response status line. */
    fun isSuccessfulSearchResponse(): Boolean = SsdpParser.isSuccessfulSearchResponse(startLine)

    /** First reason this datagram cannot become a renderer-description candidate, or null if valid. */
    fun candidateRejection(): SsdpCandidateRejection? = when {
        !isSuccessfulSearchResponse() -> SsdpCandidateRejection.STATUS
        usn.isNullOrBlank() -> SsdpCandidateRejection.MISSING_USN
        st.isNullOrBlank() -> SsdpCandidateRejection.MISSING_SEARCH_TARGET
        location.isNullOrBlank() -> SsdpCandidateRejection.MISSING_LOCATION
        !isMediaRenderer() -> SsdpCandidateRejection.NOT_MEDIA_RENDERER
        !SsdpParser.isAcceptableLocation(location) -> SsdpCandidateRejection.UNSAFE_LOCATION
        else -> null
    }

    /** True if this looks like a UPnP MediaRenderer advertisement. */
    fun isMediaRenderer(): Boolean {
        val t = (st ?: "") + (usn ?: "")
        return t.contains("MediaRenderer", ignoreCase = true)
    }
}

enum class SsdpCandidateRejection(val diagnostic: String) {
    STATUS("non-success response status"),
    MISSING_USN("missing USN"),
    MISSING_SEARCH_TARGET("missing ST"),
    MISSING_LOCATION("missing LOCATION"),
    NOT_MEDIA_RENDERER("response is not a MediaRenderer"),
    UNSAFE_LOCATION("LOCATION is not an approved LAN URL"),
}

object SsdpParser {
    const val MAX_HEADERS = 64
    const val MAX_LINE_LEN = 2048
    const val MAX_MESSAGE_LEN = 16 * 1024

    private val SUCCESSFUL_SEARCH_RESPONSE = Regex(
        """^HTTP/1\.[01][\t ]+200(?:[\t ]+[^\r\n]*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Validate the complete SSDP response status line. A normal response includes a reason phrase
     * (`HTTP/1.1 200 OK`), while a few renderers omit it; both forms are valid. Keeping this beside
     * parsing avoids subtle call-site mistakes between prefix matching and whole-string matching.
     */
    fun isSuccessfulSearchResponse(startLine: String): Boolean =
        SUCCESSFUL_SEARCH_RESPONSE.matches(startLine)

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
                !host.equals("localhost", ignoreCase = true) &&
                u.rawUserInfo == null &&
                u.rawFragment == null &&
                u.port != 0 &&
                u.port in -1..65535 &&
                ServerUrlValidator.isPrivateHost(host)
        } catch (_: Exception) {
            false
        }
    }
}
