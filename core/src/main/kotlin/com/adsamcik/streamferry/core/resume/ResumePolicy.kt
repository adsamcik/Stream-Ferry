package com.adsamcik.streamferry.core.resume

/**
 * Pure rules for "continue where you left off". Decides whether a saved playback position should be
 * resumed (vs. starting over because it is too early or effectively finished) and whether a position is
 * worth persisting. Used for on-device local files, which have no server-side resume point. Unit-tested.
 */
object ResumePolicy {

    /** Below this many seconds in, treat as "just started" — don't resume / don't save. */
    const val MIN_RESUME_SECONDS = 10L

    /** Within this many seconds of the end, treat as finished — don't resume. */
    const val END_MARGIN_SECONDS = 15L

    /** Fraction of runtime past which playback is treated as finished. */
    const val FINISHED_FRACTION = 0.97

    /** The position to resume [savedSeconds] from, or null to start at the beginning. */
    fun resumePosition(savedSeconds: Long, durationSeconds: Long?): Long? {
        if (savedSeconds < MIN_RESUME_SECONDS) return null
        if (isFinished(savedSeconds, durationSeconds)) return null
        return savedSeconds
    }

    /** Whether [positionSeconds] is worth persisting as a resume point. */
    fun shouldSave(positionSeconds: Long, durationSeconds: Long?): Boolean {
        if (positionSeconds < MIN_RESUME_SECONDS) return false
        return !isFinished(positionSeconds, durationSeconds)
    }

    /**
     * Resume progress as a 0..1 fraction ([positionSeconds] / [durationSeconds]) for a progress bar, or
     * null when there is no position or the runtime is unknown/non-positive. Clamped to [0, 1].
     */
    fun progressFraction(positionSeconds: Long?, durationSeconds: Long?): Float? {
        val pos = positionSeconds ?: return null
        val duration = durationSeconds?.takeIf { it > 0 } ?: return null
        return (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * In-progress watched fraction (0..1) for a progress bar: prefer the server's [playedPercentage]
     * (0..100) when it indicates partial progress, else fall back to [positionSeconds]/[durationSeconds].
     * Null when the item isn't partially watched (fully watched/unwatched, or no data).
     */
    fun watchedFraction(playedPercentage: Double?, positionSeconds: Long?, durationSeconds: Long?): Float? {
        if (playedPercentage != null && playedPercentage > 0.0 && playedPercentage < 100.0) {
            return (playedPercentage / 100.0).toFloat().coerceIn(0f, 1f)
        }
        return progressFraction(positionSeconds, durationSeconds)
    }

    private fun isFinished(positionSeconds: Long, durationSeconds: Long?): Boolean {
        val duration = durationSeconds ?: return false
        if (duration <= 0) return false
        if (positionSeconds >= duration - END_MARGIN_SECONDS) return true
        return positionSeconds.toDouble() / duration >= FINISHED_FRACTION
    }
}
