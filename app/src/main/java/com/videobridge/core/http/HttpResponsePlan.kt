package com.videobridge.core.http

/**
 * Selects the correct HTTP status line and response headers for a proxied media response,
 * given the parsed [RangeParseResult]. Pure logic, fully unit-testable.
 */
object HttpResponsePlan {

    enum class Status(val code: Int, val reason: String) {
        OK(200, "OK"),
        PARTIAL_CONTENT(206, "Partial Content"),
        RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
    }

    /**
     * CORS headers attached to every proxied media response. A Google Cast receiver's player fetches
     * HLS/DASH playlists and segments via XHR (a cross-origin request from the receiver's web origin),
     * which the browser blocks without these headers — a well-documented cause of casting failures.
     *
     * A permissive origin is safe here and does NOT weaken the security model: reachability is already
     * gated by the LAN-only socket bind + the unguessable 256-bit session id in the path, and the
     * proxied bytes never contain the Jellyfin URL or token. CORS only governs which web origin may
     * *read* a response it can already request — it grants no new reachability and exposes no secret.
     */
    val CORS_HEADERS: Map<String, String> = linkedMapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "Range, Content-Type, Accept-Encoding",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type",
    )

    data class Plan(
        val status: Status,
        /** Range of bytes to stream from upstream (null for 416 / empty bodies). */
        val range: ByteRange?,
        val headers: Map<String, String>,
    )

    /**
     * @param rangeResult parsed Range request.
     * @param totalLength known entity length, or negative if unknown.
     * @param contentType resolved MIME type (must be derived from upstream/stream selection, never guessed from extension alone).
     * @param head true if this is a HEAD request (no body, but identical headers).
     */
    fun plan(
        rangeResult: RangeParseResult,
        totalLength: Long,
        contentType: String,
        @Suppress("UNUSED_PARAMETER") head: Boolean = false,
    ): Plan {
        val base = linkedMapOf(
            "Content-Type" to contentType,
            // Byte-range capable proxy advertises range support so renderers can seek.
            "Accept-Ranges" to "bytes",
        )
        base.putAll(CORS_HEADERS)
        return when (rangeResult) {
            is RangeParseResult.None, RangeParseResult.Malformed -> {
                if (totalLength >= 0) base["Content-Length"] = totalLength.toString()
                val full = if (totalLength >= 0) ByteRange(0, totalLength - 1) else null
                Plan(Status.OK, full, base)
            }
            is RangeParseResult.Satisfiable -> {
                val r = rangeResult.range
                base["Content-Range"] = HttpRange.contentRange(r, totalLength)
                if (r.endInclusive != Long.MAX_VALUE) {
                    base["Content-Length"] = r.length.toString()
                }
                Plan(Status.PARTIAL_CONTENT, r, base)
            }
            is RangeParseResult.Unsatisfiable -> {
                base["Content-Range"] = HttpRange.unsatisfiedContentRange(rangeResult.totalLength)
                base["Content-Length"] = "0"
                Plan(Status.RANGE_NOT_SATISFIABLE, null, base)
            }
        }
    }
}
