package com.videobridge.core.cast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers [ConnectRetryPolicy.shouldRetry] — the pure Cast connect retry decision. */
class ConnectRetryPolicyTest {

    private fun retry(outcome: ConnectOutcome, attemptsMade: Int, timeoutsSeen: Int) =
        ConnectRetryPolicy.shouldRetry(outcome, attemptsMade, timeoutsSeen)

    @Test fun startedNeverRetries() {
        assertFalse(retry(ConnectOutcome.STARTED, attemptsMade = 1, timeoutsSeen = 0))
    }

    @Test fun fastFailureRetriesUntilMaxAttempts() {
        assertTrue(retry(ConnectOutcome.FAILED, attemptsMade = 1, timeoutsSeen = 0))
        assertTrue(retry(ConnectOutcome.FAILED, attemptsMade = 2, timeoutsSeen = 0))
    }

    @Test fun fastFailureStopsAtMaxAttempts() {
        assertFalse(retry(ConnectOutcome.FAILED, attemptsMade = 3, timeoutsSeen = 0))
    }

    @Test fun firstTimeoutIsRetriedOnce() {
        assertTrue(retry(ConnectOutcome.TIMED_OUT, attemptsMade = 1, timeoutsSeen = 1))
    }

    @Test fun secondTimeoutIsNotRetried() {
        // A genuine timeout is rarely transient; don't multiply full-timeout waits.
        assertFalse(retry(ConnectOutcome.TIMED_OUT, attemptsMade = 2, timeoutsSeen = 2))
    }

    @Test fun timeoutThenFailureStillRetriesWithinAttempts() {
        // A timeout (1) followed by a fast failure keeps retrying (failures aren't timeout-capped).
        assertTrue(retry(ConnectOutcome.FAILED, attemptsMade = 2, timeoutsSeen = 1))
    }

    @Test fun customCapsAreHonoured() {
        assertFalse(retry2(ConnectOutcome.FAILED, attemptsMade = 1, timeoutsSeen = 0, maxAttempts = 1))
        assertTrue(retry2(ConnectOutcome.TIMED_OUT, attemptsMade = 1, timeoutsSeen = 1, maxAttempts = 5, maxTimeoutRetries = 2))
    }

    private fun retry2(
        outcome: ConnectOutcome,
        attemptsMade: Int,
        timeoutsSeen: Int,
        maxAttempts: Int = ConnectRetryPolicy.MAX_ATTEMPTS,
        maxTimeoutRetries: Int = ConnectRetryPolicy.MAX_TIMEOUT_RETRIES,
    ) = ConnectRetryPolicy.shouldRetry(outcome, attemptsMade, timeoutsSeen, maxAttempts, maxTimeoutRetries)
}
