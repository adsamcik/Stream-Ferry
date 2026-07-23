package com.videobridge.core

import com.videobridge.core.net.ConnectionLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLimiterTest {

    @Test fun perIpCapRejectsExcessFromOneClient() {
        val limiter = ConnectionLimiter(maxTotal = 100, maxPerIp = 2)
        assertTrue(limiter.tryAcquire("10.0.0.5"))
        assertTrue(limiter.tryAcquire("10.0.0.5"))
        assertFalse(limiter.tryAcquire("10.0.0.5")) // 3rd from same IP rejected
        assertTrue(limiter.tryAcquire("10.0.0.6")) // a different IP is still allowed
        assertEquals(2, limiter.activeFor("10.0.0.5"))
        assertEquals(3, limiter.activeTotal())
    }

    @Test fun globalCapRejectsExcessAcrossClients() {
        val limiter = ConnectionLimiter(maxTotal = 2, maxPerIp = 10)
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("c")) // global cap reached
        limiter.release("a")
        assertTrue(limiter.tryAcquire("c")) // slot freed
        assertEquals(2, limiter.activeTotal())
    }

    @Test fun releaseFreesPerIpAndGlobalSlots() {
        val limiter = ConnectionLimiter(maxTotal = 100, maxPerIp = 1)
        assertTrue(limiter.tryAcquire("x"))
        assertFalse(limiter.tryAcquire("x"))
        limiter.release("x")
        assertEquals(0, limiter.activeFor("x"))
        assertEquals(0, limiter.activeTotal())
        assertTrue(limiter.tryAcquire("x")) // reusable after release
    }

    @Test fun rejectedPerIpDoesNotLeakGlobalSlot() {
        val limiter = ConnectionLimiter(maxTotal = 5, maxPerIp = 1)
        assertTrue(limiter.tryAcquire("x"))
        assertFalse(limiter.tryAcquire("x")) // per-IP cap hit -> must roll back the global reservation
        assertEquals(1, limiter.activeTotal()) // not 2
        assertTrue(limiter.tryAcquire("y"))
        assertTrue(limiter.tryAcquire("z"))
        assertEquals(3, limiter.activeTotal())
    }

    @Test fun overReleaseDoesNotGoNegative() {
        val limiter = ConnectionLimiter(maxTotal = 4, maxPerIp = 4)
        limiter.release("ghost") // never acquired
        assertEquals(0, limiter.activeTotal())
        assertEquals(0, limiter.activeFor("ghost"))
    }
}
