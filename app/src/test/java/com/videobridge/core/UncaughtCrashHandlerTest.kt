package com.videobridge.core

import com.videobridge.core.diagnostics.UncaughtCrashHandler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UncaughtCrashHandlerTest {

    @Test fun capturesThenDelegatesToPrevious() {
        var captured: Throwable? = null
        var delegated: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, t -> delegated = t }
        val handler = UncaughtCrashHandler(previous, onCrash = { _, t -> captured = t })

        val ex = RuntimeException("boom")
        handler.uncaughtException(Thread.currentThread(), ex)

        assertSame(ex, captured, "crash should be captured")
        assertSame(ex, delegated, "crash must still reach the previous handler")
    }

    @Test fun delegatesEvenWhenCaptureThrows() {
        var delegated = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }
        val handler = UncaughtCrashHandler(previous, onCrash = { _, _ -> throw IllegalStateException("write failed") })

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(delegated, "a failure while writing the report must not prevent delegation")
    }

    @Test fun terminatesWhenNoPreviousHandler() {
        var captured = false
        var terminated = false
        val handler = UncaughtCrashHandler(
            previous = null,
            onCrash = { _, _ -> captured = true },
            terminate = { terminated = true },
        )

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(captured)
        assertTrue(terminated, "with no previous handler the process must be terminated")
    }

    @Test fun terminateNotCalledWhenPreviousExists() {
        var terminated = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        val handler = UncaughtCrashHandler(previous, onCrash = { _, _ -> }, terminate = { terminated = true })

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertFalse(terminated)
    }
}
