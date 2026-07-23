package com.videobridge.core.resilience

/**
 * Classifies upstream (Jellyfin) HTTP responses for the resilient streaming/paging loops (§6).
 *
 * Pure logic so it is unit-testable. The distinction matters on a spotty link: a transient gateway
 * error (502/503/504) or a connection-level hiccup should be retried with backoff, whereas a
 * definitive client error (401/403/404/410/416) means the media/session is gone and retrying would
 * only waste battery and hammer the server.
 */
object UpstreamRetry {

    /** Codes Jellyfin/transcoders may emit transiently that are safe to retry. */
    private val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)

    /** A successful media response: 200 (full) or 206 (partial/Range). */
    fun isSuccess(code: Int): Boolean = code == 200 || code == 206

    /** Whether a non-success upstream status should be retried (vs. treated as fatal). */
    fun isRetryableStatus(code: Int): Boolean = code in RETRYABLE_STATUS

    /**
     * Whether an HLS playlist/segment upstream **open** should be retried. A FAST transient failure is
     * worth a quick retry — a retryable status (5xx/429/408) or a non-timeout connection error (reset /
     * refused), which happens when a live transcode segment is momentarily not ready. A read/connect
     * TIMEOUT is NOT retried: the server is simply slow to produce the segment, so another full-timeout
     * attempt would only stall the TV longer (the receiver would give up first).
     *
     * @param responseCode the upstream status, or null if the open threw before a response.
     * @param timedOut true if the open failed with a socket/interrupted-IO timeout.
     */
    fun shouldRetryOpen(responseCode: Int?, timedOut: Boolean): Boolean = when {
        responseCode != null -> isRetryableStatus(responseCode)
        timedOut -> false
        else -> true // a non-timeout connection error (reset/refused) is quick — worth one more try
    }

    /**
     * Whether a response to a resume `Range` request actually honoured the range. A 206 starts at
     * the requested offset (no skip). A 200 returns the whole entity from byte 0, so the proxy must
     * discard the already-delivered prefix before continuing.
     */
    fun rangeHonoured(code: Int): Boolean = code == 206
}
