package com.adsamcik.streamferry.playback.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the foreground barrier that closes the `ForegroundServiceDidNotStartInTimeException` race:
 * each service-start request carries a unique token, and only its own `startForeground()` confirmation can
 * release the engine to begin the renderer handshake. (Runs on virtual time.)
 */
class ProxyForegroundLatchTest {

    @Test
    fun awaitReturnsTrueOnceMatchingRequestForegrounds() = runTest {
        val token = ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded(token)
        assertTrue(ProxyForegroundLatch.await(token, 1_000), "await should succeed once startForeground() signalled")
    }

    @Test
    fun awaitTimesOutWhenNeverForegrounded() = runTest {
        val token = ProxyForegroundLatch.arm()
        assertFalse(ProxyForegroundLatch.await(token, 1_000), "await should time out if the service never foregrounds")
    }

    @Test
    fun signalIsIdempotentForTheSameRequest() = runTest {
        val token = ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded(token)
        ProxyForegroundLatch.signalForegrounded(token) // a redundant signal must not throw
        assertTrue(ProxyForegroundLatch.await(token, 1_000))
    }

    @Test
    fun reArmingSupersedesThePreviousRequest() = runTest {
        val oldToken = ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded(oldToken)
        assertTrue(ProxyForegroundLatch.await(oldToken, 1_000))

        val currentToken = ProxyForegroundLatch.arm()
        assertFalse(ProxyForegroundLatch.await(currentToken, 1_000), "a fresh start must not inherit a prior completion")
    }

    @Test
    fun staleServiceDeliveryCannotConfirmANewerRequest() = runTest {
        val staleToken = ProxyForegroundLatch.arm()
        val currentToken = ProxyForegroundLatch.arm()
        ProxyForegroundLatch.signalForegrounded(staleToken)

        assertFalse(
            ProxyForegroundLatch.await(currentToken, 1_000),
            "a late onStartCommand from an earlier start must not release the current request",
        )
    }

    @Test
    fun signalArrivingWhileAwaitingUnblocksIt() = runTest {
        val token = ProxyForegroundLatch.arm()
        val awaiting = async { ProxyForegroundLatch.await(token, 10_000) }
        ProxyForegroundLatch.signalForegrounded(token)
        assertTrue(awaiting.await(), "a waiter must be released when the matching service start foregrounds")
    }

    @Test
    fun cancellingRequestRejectsLaterSignal() = runTest {
        val token = ProxyForegroundLatch.arm()
        ProxyForegroundLatch.cancel(token)
        ProxyForegroundLatch.signalForegrounded(token)
        assertFalse(ProxyForegroundLatch.await(token, 1_000))
    }
}
