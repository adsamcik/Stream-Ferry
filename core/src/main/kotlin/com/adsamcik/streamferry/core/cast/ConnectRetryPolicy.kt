package com.adsamcik.streamferry.core.cast

/** Outcome of a single Cast session-start attempt (§8). */
enum class ConnectOutcome {
    /** A session started (or an existing one is connected) — connected. */
    STARTED,

    /** The SDK reported `onSessionStartFailed` — usually fast and often transient. */
    FAILED,

    /** No callback within the connect timeout — a full timeout elapsed. */
    TIMED_OUT,
}

/**
 * Pure retry policy for Cast connect (§8). A Cast session start can fail transiently — the SDK fires
 * `onSessionStartFailed` quickly and a fresh attempt (drop the half-open session, re-select the route)
 * usually succeeds. The controller retries based on this policy so the user doesn't have to. It decides,
 * from the last [outcome] and how many attempts / timeouts have been spent, whether to try again:
 *
 *  - [ConnectOutcome.STARTED] -> never retry (done).
 *  - [ConnectOutcome.FAILED] (fast) -> retry until [maxAttempts] is reached; the common transient case.
 *  - [ConnectOutcome.TIMED_OUT] -> retry at most [maxTimeoutRetries] time(s): a genuine timeout is rarely
 *    transient and each retry costs another full connect timeout, so timeouts are not multiplied.
 *
 * Pure — no Android, no SDK, no time — so it is fully unit-tested.
 */
object ConnectRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val MAX_TIMEOUT_RETRIES = 1

    /**
     * @param outcome the just-observed attempt outcome.
     * @param attemptsMade how many attempts have completed so far (including this one).
     * @param timeoutsSeen how many of those attempts timed out (including this one, if it did).
     * @return true to make another attempt, false to give up and surface the failure.
     */
    fun shouldRetry(
        outcome: ConnectOutcome,
        attemptsMade: Int,
        timeoutsSeen: Int,
        maxAttempts: Int = MAX_ATTEMPTS,
        maxTimeoutRetries: Int = MAX_TIMEOUT_RETRIES,
    ): Boolean = when {
        outcome == ConnectOutcome.STARTED -> false
        attemptsMade >= maxAttempts -> false
        outcome == ConnectOutcome.FAILED -> true
        outcome == ConnectOutcome.TIMED_OUT -> timeoutsSeen <= maxTimeoutRetries
        else -> false
    }
}
