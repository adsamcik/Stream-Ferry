package com.videobridge.playback.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the foreground barrier that closes the `ForegroundServiceDidNotStartInTimeException` race:
 * the playback engine arms the latch just before `startForegroundService()` and blocks on [await] until
 * the service reports it has called `startForeground()`, only then starting the main-thread-saturating
 * renderer handshake. (Runs on virtual time — the "timeout" cases never wait in real wall-clock.)
 */
class ProxyForegroundLatchTest {

    @Test
    fun awaitReturnsTrueOnceForegrounded() = runTest {
        ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded()
        assertTrue(ProxyForegroundLatch.await(1_000), "await should succeed once startForeground() signalled")
    }

    @Test
    fun awaitTimesOutWhenNeverForegrounded() = runTest {
        ProxyForegroundLatch.arm()
        assertFalse(ProxyForegroundLatch.await(1_000), "await should time out (false) if the service never foregrounds")
    }

    @Test
    fun signalIsIdempotent() = runTest {
        ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded()
        ProxyForegroundLatch.signalForegrounded() // a second, redundant signal must not throw
        assertTrue(ProxyForegroundLatch.await(1_000))
    }

    @Test
    fun reArmingResetsTheLatch() = runTest {
        ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded()
        assertTrue(ProxyForegroundLatch.await(1_000))

        // A fresh start must not be considered already-foregrounded by a stale completion.
        ProxyForegroundLatch.arm()
        assertFalse(ProxyForegroundLatch.await(1_000), "re-arm must supersede the previous (completed) latch")
    }

    @Test
    fun signalArrivingWhileAwaitingUnblocksIt() = runTest {
        ProxyForegroundLatch.arm()
        val awaiting = async { ProxyForegroundLatch.await(10_000) }
        ProxyForegroundLatch.signalForegrounded()
        assertTrue(awaiting.await(), "a waiter must be released when the service foregrounds")
    }
}
