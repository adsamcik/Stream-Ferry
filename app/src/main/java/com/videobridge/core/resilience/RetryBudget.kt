package com.videobridge.core.resilience

import kotlin.math.pow

/**
 * Bounded retry/backoff policy (§5/§6 resilience).
 *
 * Pure-JVM, framework-free so it is exhaustively unit-testable. Used by the streaming proxy
 * (upstream stall recovery) and by library paging (transient page-fetch recovery). All limits are
 * bounded constants: there is no unbounded retry loop, and backoff is capped so a flapping link
 * cannot busy-spin.
 *
 * @param maxConsecutiveFailures how many times in a row a recoverable failure may be retried before
 *   the operation gives up. Reset to zero by [ResilientStreamPolicy] after sustained progress so a
 *   long movie with occasional dips is not bounded by a single global budget.
 * @param baseDelayMillis delay before the first retry.
 * @param maxDelayMillis hard ceiling for any single backoff delay.
 * @param multiplier exponential growth factor per consecutive failure.
 * @param jitterFraction fraction of the delay that is randomised, to de-correlate reconnects.
 */
data class RetryBudget(
    val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MS,
    val multiplier: Double = DEFAULT_MULTIPLIER,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
) {
    init {
        require(maxConsecutiveFailures >= 0) { "maxConsecutiveFailures must be >= 0" }
        require(baseDelayMillis >= 0) { "baseDelayMillis must be >= 0" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in [0,1]" }
    }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 6
        const val DEFAULT_BASE_DELAY_MS = 250L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
        const val DEFAULT_MULTIPLIER = 2.0
        const val DEFAULT_JITTER_FRACTION = 0.2
    }
}

/**
 * Deterministic, fully-testable backoff computation. The randomness is injected via [jitterRoll]
 * (a value in [0,1]) so unit tests can pin exact delays; production passes a real RNG sample.
 */
object Backoff {

    /**
     * Compute the delay (ms) before the [attempt]-th retry (1-based: the first retry uses the base
     * delay). The exponential value is capped at [RetryBudget.maxDelayMillis] BEFORE jitter, then
     * "equal jitter" is applied: delay in [cap*(1-f), cap] where f = jitterFraction.
     */
    fun delayMillis(budget: RetryBudget, attempt: Int, jitterRoll: Double = 0.5): Long {
        if (attempt <= 0) return 0L
        val exp = budget.baseDelayMillis.toDouble() * budget.multiplier.pow(attempt - 1)
        val capped = exp.coerceAtMost(budget.maxDelayMillis.toDouble())
        val roll = jitterRoll.coerceIn(0.0, 1.0)
        val floor = capped * (1.0 - budget.jitterFraction)
        val delay = floor + roll * (capped - floor)
        return delay.toLong().coerceIn(0L, budget.maxDelayMillis)
    }
}
