package com.videobridge.core.diagnostics

/**
 * Framework-free [Thread.UncaughtExceptionHandler] that records a crash and then hands off to the
 * previously-installed handler (so the process still terminates the normal way). Pure-JVM so the
 * "capture must never hide or change the crash" guarantees are unit-testable.
 *
 * @param previous the handler that was installed before this one (delegated to after capture).
 * @param onCrash records the crash (write a report); any failure here is swallowed so it can never
 *   prevent delegation/termination.
 * @param terminate invoked only when there is no [previous] handler (last-resort process exit).
 */
class UncaughtCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val onCrash: (Thread, Throwable) -> Unit,
    private val terminate: () -> Unit = {},
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Capturing the report must never throw past this point and hide the original crash.
        runCatching { onCrash(thread, throwable) }
        val prior = previous
        if (prior != null) prior.uncaughtException(thread, throwable) else terminate()
    }
}
