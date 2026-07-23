package com.videobridge.core.resilience

/**
 * Resilience policy for a single proxied media transfer (§6).
 *
 * The local proxy streams upstream Jellyfin bytes to the TV through a small bounded buffer. On a
 * spotty link the *upstream* read can stall or reset mid-transfer even though average throughput is
 * fine. Without recovery the TV sees a truncated/broken stream. This policy lets the proxy
 * transparently reconnect to the upstream at the exact next byte (an HTTP Range resume) and keep
 * feeding the already-open downstream connection, so the renderer never observes the dip.
 *
 * Pure-JVM and deterministic: it owns only counters + decisions. The proxy performs the actual
 * socket/HTTP work and the backoff sleep. One instance is used per in-flight request.
 *
 * Security: this type never holds the upstream URL or auth header, so it can be logged freely.
 *
 * @param rangeStart absolute file offset of the first byte this request must deliver (0 for a full
 *   response, or the start of the client's requested Range).
 * @param rangeEndInclusive absolute file offset of the last byte to deliver, or null for open-ended
 *   (stream to EOF — e.g. unknown-length transcode).
 * @param budget retry/backoff configuration.
 * @param progressResetBytes once this many bytes are forwarded with no failure, the consecutive
 *   failure counter resets so a long, mostly-healthy stream keeps its full recovery budget.
 */
class ResilientStreamPolicy(
    val rangeStart: Long,
    val rangeEndInclusive: Long? = null,
    val budget: RetryBudget = RetryBudget(),
    val progressResetBytes: Long = DEFAULT_PROGRESS_RESET_BYTES,
) {
    init {
        require(rangeStart >= 0) { "rangeStart must be >= 0" }
        require(rangeEndInclusive == null || rangeEndInclusive >= rangeStart) {
            "rangeEndInclusive must be >= rangeStart"
        }
        require(progressResetBytes > 0) { "progressResetBytes must be > 0" }
    }

    /** Total bytes successfully forwarded downstream so far. */
    var bytesForwarded: Long = 0L
        private set

    /** Consecutive recoverable failures since the last sustained-progress reset. */
    var consecutiveFailures: Int = 0
        private set

    private var bytesSinceLastFailure: Long = 0L

    /** Absolute file offset of the next byte the downstream still needs. */
    val nextOffset: Long get() = rangeStart + bytesForwarded

    /** Whether everything the client asked for has been delivered (clean completion). */
    val isComplete: Boolean
        get() = rangeEndInclusive != null && nextOffset > rangeEndInclusive

    /** Record [n] bytes successfully written downstream. Resets the budget after sustained progress. */
    fun recordProgress(n: Long) {
        if (n <= 0) return
        bytesForwarded += n
        bytesSinceLastFailure += n
        if (bytesSinceLastFailure >= progressResetBytes) {
            consecutiveFailures = 0
            bytesSinceLastFailure = 0L
        }
    }

    sealed interface Decision {
        /** Reconnect upstream after [delayMillis], resuming at [resumeFromOffset]. */
        data class Retry(val delayMillis: Long, val resumeFromOffset: Long, val attempt: Int) : Decision
        /** Budget exhausted (or nothing left to fetch): stop trying. */
        data object GiveUp : Decision
    }

    /**
     * Called after a *recoverable upstream* failure (read timeout, connection reset, premature EOF,
     * retryable 5xx). Returns whether/how to resume. [jitterRoll] in [0,1] feeds [Backoff].
     */
    fun onRecoverableFailure(jitterRoll: Double = 0.5): Decision {
        if (isComplete) return Decision.GiveUp
        consecutiveFailures += 1
        bytesSinceLastFailure = 0L
        if (consecutiveFailures > budget.maxConsecutiveFailures) return Decision.GiveUp
        val delay = Backoff.delayMillis(budget, consecutiveFailures, jitterRoll)
        return Decision.Retry(delayMillis = delay, resumeFromOffset = nextOffset, attempt = consecutiveFailures)
    }

    /**
     * Build the upstream `Range` header value to resume the transfer from [nextOffset], bounded by
     * the original requested end. Returns e.g. `bytes=1048576-` (open) or `bytes=1048576-4193279`.
     */
    fun resumeRangeHeader(): String {
        val end = rangeEndInclusive
        return if (end == null) "bytes=$nextOffset-" else "bytes=$nextOffset-$end"
    }

    companion object {
        /** 4 MiB of clean progress restores the full retry budget. */
        const val DEFAULT_PROGRESS_RESET_BYTES = 4L * 1024 * 1024
    }
}
