package com.videobridge.playback

import com.videobridge.domain.PlaybackFailureKind

/**
 * Pure heuristics for [PlaybackEngine]'s startup watchdog (ideas 1+7): deciding when a stream that never
 * demonstrably started playing should be treated as a failure. Extracted so the (necessarily heuristic)
 * thresholds are unit-tested; the watchdog machinery itself (timers, cancellation, state) lives in the
 * engine. A silently-failing renderer emits no error event, so this is the safety net that still triggers
 * recovery.
 */
object StartupWatchdog {
    /** How long to wait for the renderer to start playing before assuming a silent failure. Generous, so
     *  a slow-but-healthy start isn't cut off; [isEarlyRejection] catches obvious rejections much sooner. */
    const val GRACE_MS = 12_000L

    /** ">= this many bytes flowed but it never played" reads as a decode/format failure (transcodable);
     *  less means the TV never really fetched (a connectivity/URL problem transcoding wouldn't fix). */
    const val MIN_PROGRESS_BYTES = 256L * 1024

    /** Lower bound for the fast path: a close under this is a range/HEAD probe, not a real read. */
    const val PROBE_BYTES = 256L * 1024

    /** Upper bound for the fast path: a close over this read enough that it was probably buffering fine. */
    const val VIABLE_BYTES = 8L * 1024 * 1024

    /** A close later than this isn't a snappy "tried then bailed" rejection. */
    const val FAST_FAIL_MS = 8_000L

    /** The failure kind to recover with when [GRACE_MS] elapses without playback starting. */
    fun graceTimeoutKind(bytesServed: Long): PlaybackFailureKind =
        if (bytesServed >= MIN_PROGRESS_BYTES) PlaybackFailureKind.FORMAT else PlaybackFailureKind.UNKNOWN

    /**
     * True if a downstream connection that delivered [bytesServed] bytes over [durationMs] ms looks like a
     * "tried to play, then bailed" rejection — read more than a probe but less than a viable amount, and
     * closed quickly. Used only before playback has started, so a false positive merely triggers an early
     * (harmless) transcode instead of aborting healthy playback.
     */
    fun isEarlyRejection(bytesServed: Long, durationMs: Long): Boolean =
        bytesServed in PROBE_BYTES until VIABLE_BYTES && durationMs < FAST_FAIL_MS
}
