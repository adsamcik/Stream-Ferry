package com.adsamcik.streamferry.core.http

/**
 * RFC 9110 (§14) single byte-range parsing and resolution for the local memory proxy.
 *
 * This is intentionally a pure-JVM, framework-free unit so it can be exhaustively unit tested
 * without Android. The proxy server wraps this logic inside its socket/coroutine handler.
 *
 * Only a single byte range is supported (multipart/byteranges is intentionally NOT implemented
 * for the MVP; media players and TV renderers use single ranges for seeking).
 */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

sealed interface RangeParseResult {
    /** No Range header present: caller should serve a 200 with the full entity. */
    data object None : RangeParseResult

    /** A satisfiable single range. */
    data class Satisfiable(val range: ByteRange) : RangeParseResult

    /** Range syntactically valid but not satisfiable for the known length: caller serves 416. */
    data class Unsatisfiable(val totalLength: Long) : RangeParseResult

    /** Header was malformed; per RFC the server SHOULD ignore it and serve 200. */
    data object Malformed : RangeParseResult
}

object HttpRange {

    private val SINGLE_RANGE = Regex("^bytes=(\\d*)-(\\d*)$")

    /**
     * Parse a Range request header against a known total content length.
     *
     * @param header raw value of the `Range` header, or null when absent.
     * @param totalLength the full length of the upstream entity. Pass a negative value when the
     *   length is unknown (e.g. chunked/transcoded HLS upstream); in that case a satisfiable
     *   open-ended range starting at the requested offset is returned with [Long.MAX_VALUE] end.
     */
    fun parse(header: String?, totalLength: Long): RangeParseResult {
        if (header.isNullOrBlank()) return RangeParseResult.None
        val match = SINGLE_RANGE.matchEntire(header.trim()) ?: return RangeParseResult.Malformed
        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]
        if (startStr.isEmpty() && endStr.isEmpty()) return RangeParseResult.Malformed

        // Suffix range: bytes=-N  (last N bytes)
        if (startStr.isEmpty()) {
            val suffix = endStr.toLongOrNull() ?: return RangeParseResult.Malformed
            if (suffix <= 0) return RangeParseResult.Malformed
            if (totalLength < 0) {
                // Unknown length: cannot resolve a suffix range safely.
                return RangeParseResult.Malformed
            }
            if (totalLength == 0L) return RangeParseResult.Unsatisfiable(totalLength)
            val start = (totalLength - suffix).coerceAtLeast(0L)
            return RangeParseResult.Satisfiable(ByteRange(start, totalLength - 1))
        }

        val start = startStr.toLongOrNull() ?: return RangeParseResult.Malformed
        if (start < 0) return RangeParseResult.Malformed

        if (totalLength >= 0 && start >= totalLength) {
            return RangeParseResult.Unsatisfiable(totalLength)
        }

        val end: Long = if (endStr.isEmpty()) {
            if (totalLength >= 0) totalLength - 1 else Long.MAX_VALUE
        } else {
            val e = endStr.toLongOrNull() ?: return RangeParseResult.Malformed
            if (e < start) return RangeParseResult.Malformed
            if (totalLength >= 0) minOf(e, totalLength - 1) else e
        }
        return RangeParseResult.Satisfiable(ByteRange(start, end))
    }

    /** Build a `Content-Range` header value for a 206 response. */
    fun contentRange(range: ByteRange, totalLength: Long): String {
        val total = if (totalLength >= 0) totalLength.toString() else "*"
        return "bytes ${range.start}-${range.endInclusive}/$total"
    }

    /** Build the `Content-Range` header value for a 416 response. */
    fun unsatisfiedContentRange(totalLength: Long): String = "bytes */$totalLength"
}
