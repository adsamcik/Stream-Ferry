package com.adsamcik.streamferry.core.http

/**
 * Verifies that an upstream response can safely back the byte range the phone proxy is about to
 * advertise to a renderer. A successful HTTP response alone is insufficient: an origin is allowed
 * to ignore a Range request and return 200 from byte zero.
 */
object UpstreamRangeVerifier {

    private data class ParsedContentRange(val start: Long, val endInclusive: Long, val totalLength: Long)

    private val CONTENT_RANGE = Regex(
        """^bytes\s+(\d+)-(\d+)/(\d+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNSATISFIED_CONTENT_RANGE = Regex(
        """^bytes\s+\*/(\d+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val REQUEST_RANGE = Regex(
        """^bytes=(\d*)-(\d*)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A proxy may emit the requested 206 only when the upstream returned exactly that window and
     * agrees on the entity length known by the proxy session.
     */
    fun isExact(
        statusCode: Int,
        contentRanges: List<String>,
        contentLength: Long?,
        requested: ByteRange,
        expectedTotalLength: Long,
    ): Boolean {
        val actual = parseContentRange(statusCode, contentRanges, contentLength) ?: return false
        return actual.start == requested.start &&
            actual.endInclusive == requested.endInclusive &&
            actual.totalLength == expectedTotalLength
    }

    /**
     * Validate a renderer's single-byte-range request when the segment total is learned from the
     * upstream Content-Range. Multiple ranges are rejected because this proxy does not emit multipart
     * responses; suffix and open-ended forms are accepted only when the returned span proves them.
     */
    fun matchesRequestHeader(
        statusCode: Int,
        contentRanges: List<String>,
        contentLength: Long?,
        requestHeader: String,
    ): Boolean {
        val requested = REQUEST_RANGE.matchEntire(requestHeader.trim()) ?: return false
        val actual = parseContentRange(statusCode, contentRanges, contentLength) ?: return false
        val requestedStart = requested.groupValues[1].toLongOrNull()
        val requestedEnd = requested.groupValues[2].toLongOrNull()
        if (requestedStart == null && requestedEnd == null) return false
        return when {
            requestedStart != null && requestedEnd != null ->
                requestedEnd >= requestedStart &&
                    actual.start == requestedStart && actual.endInclusive == requestedEnd
            requestedStart != null ->
                actual.start == requestedStart && actual.endInclusive == actual.totalLength - 1
            else -> {
                val suffixLength = requestedEnd ?: return false
                suffixLength > 0 &&
                    actual.endInclusive == actual.totalLength - 1 &&
                    spanLength(actual.start, actual.endInclusive) == minOf(suffixLength, actual.totalLength)
            }
        }
    }

    /** Return the proven entity length from a canonical unsatisfied-range response. */
    fun unsatisfiedTotal(statusCode: Int, contentRanges: List<String>): Long? {
        if (statusCode != 416 || contentRanges.size != 1) return null
        val match = UNSATISFIED_CONTENT_RANGE.matchEntire(contentRanges.single().trim()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun parseContentRange(
        statusCode: Int,
        contentRanges: List<String>,
        contentLength: Long?,
    ): ParsedContentRange? {
        if (statusCode != 206 || contentRanges.size != 1 || contentLength?.let { it < 0 } == true) return null
        val match = CONTENT_RANGE.matchEntire(contentRanges.single().trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (total <= 0 || start > end || end >= total) return null
        val span = spanLength(start, end) ?: return null
        if (contentLength != null && contentLength != span) return null
        return ParsedContentRange(start, end, total)
    }

    private fun spanLength(start: Long, endInclusive: Long): Long? =
        runCatching { Math.addExact(Math.subtractExact(endInclusive, start), 1L) }.getOrNull()
}
