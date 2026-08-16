package com.adsamcik.streamferry.core.resilience

/**
 * Detects a slow-trickle upstream that delivers just enough bytes to dodge the socket read-timeout
 * (so the resilient reconnect never fires) yet far too few to sustain playback — bounding it instead
 * of letting the transfer hang until the session TTL (§16). Time-based and pure-JVM: the proxy feeds
 * it the byte counts it delivers downstream together with a clock, and [record] returns true once
 * throughput has stayed below the floor for [maxLowWindows] consecutive windows (after a grace period).
 *
 * It deliberately does NOT fire on a paused/slow TV: when the downstream (TV) stops reading, the proxy
 * blocks on the write (backpressure) and never calls [record], so a long gap between records is read
 * as a pause and restarts the window rather than counting as a stall. The floor is set far below any
 * real video bitrate, so it only trips on a genuinely dead/hostile trickle.
 */
class ThroughputWatchdog(
    val minBytesPerWindow: Long = DEFAULT_MIN_BYTES_PER_WINDOW,
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    val maxLowWindows: Int = DEFAULT_MAX_LOW_WINDOWS,
) {
    private var startMillis = -1L
    private var windowStartMillis = -1L
    private var lastRecordMillis = -1L
    private var bytesInWindow = 0L
    private var lowWindows = 0

    /**
     * Record [bytes] delivered downstream at [nowMillis]. Returns true if the transfer should be given
     * up because throughput has stayed below [minBytesPerWindow] for [maxLowWindows] consecutive
     * windows (after the initial grace period).
     */
    fun record(bytes: Long, nowMillis: Long): Boolean {
        if (startMillis < 0L) {
            startMillis = nowMillis
            windowStartMillis = nowMillis
            lastRecordMillis = nowMillis
        }
        // A long gap since the last delivered byte means we were blocked on a paused/slow TV
        // (downstream backpressure), not a slow upstream — restart the window, never a stall.
        if (nowMillis - lastRecordMillis > windowMillis) {
            windowStartMillis = nowMillis
            bytesInWindow = 0L
            lowWindows = 0
        }
        lastRecordMillis = nowMillis
        if (bytes > 0L) bytesInWindow += bytes

        if (nowMillis - startMillis < graceMillis) return false
        if (nowMillis - windowStartMillis >= windowMillis) {
            if (bytesInWindow < minBytesPerWindow) lowWindows += 1 else lowWindows = 0
            bytesInWindow = 0L
            windowStartMillis = nowMillis
            if (lowWindows >= maxLowWindows) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MIN_BYTES_PER_WINDOW = 32L * 1024 // ~13 kbps over a 20s window — far below any video rung
        const val DEFAULT_WINDOW_MILLIS = 20_000L
        const val DEFAULT_GRACE_MILLIS = 30_000L
        const val DEFAULT_MAX_LOW_WINDOWS = 3 // ~60s of sustained near-dead throughput after the grace period
    }
}
