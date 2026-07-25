package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.resilience.Backoff
import com.adsamcik.streamferry.core.resilience.LibraryPagingPolicy
import com.adsamcik.streamferry.core.resilience.ResilientStreamPolicy
import com.adsamcik.streamferry.core.resilience.RetryBudget
import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResilienceTest {

    private val budget = RetryBudget() // base=250, max=8000, mult=2.0, jitter=0.2

    // ----- Backoff -----

    @Test fun backoff_zeroForNonPositiveAttempt() {
        assertEquals(0L, Backoff.delayMillis(budget, 0))
        assertEquals(0L, Backoff.delayMillis(budget, -3))
    }

    @Test fun backoff_equalJitterBounds() {
        // attempt 1: capped=250, floor=200 -> roll 0 => 200, roll 1 => 250, roll 0.5 => 225
        assertEquals(200L, Backoff.delayMillis(budget, 1, jitterRoll = 0.0))
        assertEquals(250L, Backoff.delayMillis(budget, 1, jitterRoll = 1.0))
        assertEquals(225L, Backoff.delayMillis(budget, 1, jitterRoll = 0.5))
    }

    @Test fun backoff_growsExponentiallyThenCaps() {
        assertEquals(500L, Backoff.delayMillis(budget, 2, jitterRoll = 1.0))   // 250*2
        assertEquals(1000L, Backoff.delayMillis(budget, 3, jitterRoll = 1.0))  // 250*4
        assertEquals(8000L, Backoff.delayMillis(budget, 6, jitterRoll = 1.0))  // 250*32
        // Beyond the cap stays at the cap (never unbounded).
        assertEquals(8000L, Backoff.delayMillis(budget, 9, jitterRoll = 1.0))
        assertEquals(6400L, Backoff.delayMillis(budget, 9, jitterRoll = 0.0)) // floor = 8000*0.8
    }

    @Test fun backoff_jitterRollClamped() {
        assertEquals(250L, Backoff.delayMillis(budget, 1, jitterRoll = 9.0))
        assertEquals(200L, Backoff.delayMillis(budget, 1, jitterRoll = -9.0))
    }

    // ----- ResilientStreamPolicy -----

    @Test fun stream_resumeOffsetAndHeader_openEnded() {
        val p = ResilientStreamPolicy(rangeStart = 0)
        assertEquals(0L, p.nextOffset)
        p.recordProgress(1_048_576)
        assertEquals(1_048_576L, p.nextOffset)
        assertEquals("bytes=1048576-", p.resumeRangeHeader())
        assertFalse(p.isComplete)
    }

    @Test fun stream_resumeOffsetAndHeader_boundedRange() {
        val p = ResilientStreamPolicy(rangeStart = 1000, rangeEndInclusive = 4999)
        p.recordProgress(500)
        assertEquals(1500L, p.nextOffset)
        assertEquals("bytes=1500-4999", p.resumeRangeHeader())
    }

    @Test fun stream_completesWhenRangeDelivered() {
        val p = ResilientStreamPolicy(rangeStart = 0, rangeEndInclusive = 999)
        p.recordProgress(1000)
        assertTrue(p.isComplete)
        assertIs<ResilientStreamPolicy.Decision.GiveUp>(p.onRecoverableFailure())
    }

    @Test fun stream_retriesUntilBudgetExhausted() {
        val b = RetryBudget(maxConsecutiveFailures = 3)
        val p = ResilientStreamPolicy(rangeStart = 0, budget = b)
        val d1 = p.onRecoverableFailure(jitterRoll = 0.0); assertIs<ResilientStreamPolicy.Decision.Retry>(d1)
        assertEquals(1, d1.attempt)
        assertEquals(0L, d1.resumeFromOffset)
        assertIs<ResilientStreamPolicy.Decision.Retry>(p.onRecoverableFailure())
        assertIs<ResilientStreamPolicy.Decision.Retry>(p.onRecoverableFailure())
        // 4th consecutive failure exceeds budget of 3 -> give up.
        assertIs<ResilientStreamPolicy.Decision.GiveUp>(p.onRecoverableFailure())
    }

    @Test fun stream_sustainedProgressRestoresBudget() {
        val b = RetryBudget(maxConsecutiveFailures = 2)
        val p = ResilientStreamPolicy(rangeStart = 0, budget = b, progressResetBytes = 10)
        assertIs<ResilientStreamPolicy.Decision.Retry>(p.onRecoverableFailure())
        assertIs<ResilientStreamPolicy.Decision.Retry>(p.onRecoverableFailure())
        // Budget would be exhausted next, but a healthy burst resets it.
        p.recordProgress(10)
        assertEquals(0, p.consecutiveFailures)
        assertIs<ResilientStreamPolicy.Decision.Retry>(p.onRecoverableFailure())
    }

    @Test fun stream_resumeOffsetTracksAcrossFailures() {
        val p = ResilientStreamPolicy(rangeStart = 100)
        p.recordProgress(900)
        val d = p.onRecoverableFailure() as ResilientStreamPolicy.Decision.Retry
        assertEquals(1000L, d.resumeFromOffset)
        assertEquals("bytes=1000-", p.resumeRangeHeader())
    }

    // ----- UpstreamRetry -----

    @Test fun upstream_statusClassification() {
        assertTrue(UpstreamRetry.isSuccess(200))
        assertTrue(UpstreamRetry.isSuccess(206))
        assertFalse(UpstreamRetry.isSuccess(204))
        assertTrue(UpstreamRetry.isRetryableStatus(503))
        assertTrue(UpstreamRetry.isRetryableStatus(500))
        assertTrue(UpstreamRetry.isRetryableStatus(429))
        assertFalse(UpstreamRetry.isRetryableStatus(404))
        assertFalse(UpstreamRetry.isRetryableStatus(416))
        assertFalse(UpstreamRetry.isRetryableStatus(401))
        assertTrue(UpstreamRetry.rangeHonoured(206))
        assertFalse(UpstreamRetry.rangeHonoured(200))
    }

    @Test fun upstream_hlsOpenRetryDecision() {
        // Fast transient statuses -> retry; definitive client errors -> don't.
        assertTrue(UpstreamRetry.shouldRetryOpen(responseCode = 503, timedOut = false))
        assertTrue(UpstreamRetry.shouldRetryOpen(responseCode = 500, timedOut = false))
        assertFalse(UpstreamRetry.shouldRetryOpen(responseCode = 404, timedOut = false))
        assertFalse(UpstreamRetry.shouldRetryOpen(responseCode = 401, timedOut = false))
        // Connection error (no response): retry a fast reset/refused, but NOT a slow timeout.
        assertTrue(UpstreamRetry.shouldRetryOpen(responseCode = null, timedOut = false))
        assertFalse(UpstreamRetry.shouldRetryOpen(responseCode = null, timedOut = true))
    }

    // ----- LibraryPagingPolicy -----

    @Test fun paging_clampsPageSize() {
        assertEquals(500, LibraryPagingPolicy(pageSize = 5000).pageSize)
        assertEquals(1, LibraryPagingPolicy(pageSize = 0).pageSize)
        assertEquals(200, LibraryPagingPolicy().pageSize)
    }

    @Test fun paging_walksToTotal() {
        val policy = LibraryPagingPolicy(pageSize = 200)
        val first = policy.firstPage()
        assertEquals(LibraryPagingPolicy.PageRequest(0, 200), first)
        val second = policy.nextPage(first, itemsInPage = 200, totalRecordCount = 450)
        assertEquals(LibraryPagingPolicy.PageRequest(200, 200), second)
        val third = policy.nextPage(second!!, itemsInPage = 200, totalRecordCount = 450)
        assertEquals(LibraryPagingPolicy.PageRequest(400, 200), third)
        // 400 + 50 = 450 >= total -> done.
        assertNull(policy.nextPage(third!!, itemsInPage = 50, totalRecordCount = 450))
    }

    @Test fun paging_shortPageEndsEvenWithUnknownTotal() {
        val policy = LibraryPagingPolicy(pageSize = 200)
        val first = policy.firstPage()
        assertNull(policy.nextPage(first, itemsInPage = 150, totalRecordCount = null))
        // Full page with unknown total continues.
        assertEquals(
            LibraryPagingPolicy.PageRequest(200, 200),
            policy.nextPage(first, itemsInPage = 200, totalRecordCount = null),
        )
        // Empty page ends.
        assertNull(policy.nextPage(first, itemsInPage = 0, totalRecordCount = null))
    }

    @Test fun paging_stopsAtMaxPagesCeiling() {
        // A server that always returns a full page with no total would loop forever without a ceiling.
        val policy = LibraryPagingPolicy(pageSize = 100, maxPages = 3)
        var req = policy.firstPage()
        var pages = 1
        while (true) {
            val next = policy.nextPage(req, itemsInPage = 100, totalRecordCount = null) ?: break
            req = next
            pages++
        }
        assertEquals(3, pages)
    }

    @Test fun paging_clampsMaxPages() {
        assertEquals(1, LibraryPagingPolicy(maxPages = 0).maxPages)
        assertEquals(1, LibraryPagingPolicy(maxPages = -5).maxPages)
    }
}
