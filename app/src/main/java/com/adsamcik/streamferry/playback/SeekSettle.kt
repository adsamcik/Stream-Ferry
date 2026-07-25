package com.adsamcik.streamferry.playback

import kotlin.math.abs

/**
 * Pure helper deciding whether a renderer status update should be applied or briefly held after a seek.
 *
 * A seek is reflected on the phone optimistically (the scrubber jumps to the target immediately). But a
 * status poll that was already in flight when the seek was issued — common on DLNA, which polls the
 * renderer for position rather than pushing updates — can report the OLD position and snap the scrubber
 * backward, so the phone appears to lag behind the TV (which seeks instantly). To keep the two in sync,
 * such a contradicting report is ignored until the renderer confirms a position near the target, or a
 * short settle window elapses (the backstop for a seek the renderer clamped or rejected).
 */
object SeekSettle {
    /**
     * @param targetSeconds the seek target being settled, or null when no seek is pending (guard inactive).
     * @param incomingSeconds the absolute position reported by the renderer status under consideration.
     * @param nowMs the current time in ms.
     * @param untilMs the instant the hold window expires (in the same clock as [nowMs]).
     * @param toleranceSeconds how close [incomingSeconds] must be to [targetSeconds] to count as confirmed.
     * @return true to HOLD (ignore this status, keep showing the target); false to APPLY it.
     */
    fun shouldHold(
        targetSeconds: Long?,
        incomingSeconds: Long,
        nowMs: Long,
        untilMs: Long,
        toleranceSeconds: Long,
    ): Boolean {
        if (targetSeconds == null) return false
        val nearTarget = abs(incomingSeconds - targetSeconds) <= toleranceSeconds
        return !nearTarget && nowMs < untilMs
    }
}
