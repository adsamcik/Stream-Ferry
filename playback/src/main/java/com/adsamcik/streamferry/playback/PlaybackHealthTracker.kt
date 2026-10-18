package com.adsamcik.streamferry.playback

/** Renderer-confirmed progress, independent of relay throughput and encoder backpressure. */
internal class PlaybackHealthTracker {
    private var generation: Long? = null
    private var position = 0L
    private var lastReportMs = 0L
    private var lastProgressMs = 0L
    private var healthySinceMs: Long? = null
    private var progressed = false

    @Synchronized fun reset() {
        generation = null
        healthySinceMs = null
        progressed = false
    }

    /** Only confirmed PLAYING reports outside seek/buffering/replacement windows are eligible. */
    @Synchronized fun observe(generation: Long, positionSeconds: Long, nowMs: Long): Boolean {
        val gap = nowMs - lastReportMs
        val jump = positionSeconds - position
        if (this.generation != generation || gap !in 0..MAX_REPORT_GAP_MS ||
            jump < 0 || jump > gap / 1000 * 4 + 5
        ) {
            this.generation = generation
            position = positionSeconds
            lastReportMs = nowMs
            lastProgressMs = nowMs
            healthySinceMs = nowMs
            progressed = false
            return false
        }
        lastReportMs = nowMs
        val advanced = positionSeconds > position
        if (advanced) {
            lastProgressMs = nowMs
            if (healthySinceMs == null) healthySinceMs = nowMs
            progressed = true
        } else if (nowMs - lastProgressMs >= MAX_REPORT_GAP_MS) {
            healthySinceMs = null
            progressed = false
        }
        position = positionSeconds
        return advanced && progressed && healthySinceMs?.let { nowMs - it >= HEALTHY_WINDOW_MS } == true
    }

    @Synchronized fun isStalled(generation: Long, nowMs: Long): Boolean =
        this.generation == generation && nowMs - lastReportMs in 0..MAX_REPORT_GAP_MS &&
            nowMs - lastProgressMs >= STALL_WINDOW_MS

    companion object {
        const val HEALTHY_WINDOW_MS = 60_000L
        const val STALL_WINDOW_MS = 45_000L
        private const val MAX_REPORT_GAP_MS = 15_000L
    }
}
