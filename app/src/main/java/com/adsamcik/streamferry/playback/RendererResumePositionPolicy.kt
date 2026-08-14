package com.adsamcik.streamferry.playback

/**
 * State for confirming that a newly loaded renderer timeline actually started at the requested point.
 *
 * Cast can acknowledge a load containing `currentTime` before the receiver has applied that position.
 * DLNA applies its load-time position with a deferred seek. In either case the first PLAYING status is
 * the reliable point at which the sender can confirm the result and, if necessary, issue a bounded
 * corrective seek.
 */
internal data class PendingRendererResumePosition(
    val loadGeneration: Long,
    val expectedPositionSeconds: Long,
    val correctionsIssued: Int = 0,
    val correctionInFlight: Boolean = false,
)

internal sealed interface RendererResumePositionAction {
    data object Wait : RendererResumePositionAction
    data object Confirmed : RendererResumePositionAction
    data class Correct(val positionSeconds: Long) : RendererResumePositionAction
    data object GiveUp : RendererResumePositionAction
}

internal object RendererResumePositionPolicy {
    const val MAX_CORRECTIONS = 2
    const val TOLERANCE_SECONDS = 5L

    fun evaluate(
        pending: PendingRendererResumePosition,
        reportedPositionSeconds: Long,
        isPlaying: Boolean,
    ): RendererResumePositionAction {
        if (!isPlaying) return RendererResumePositionAction.Wait
        val reported = reportedPositionSeconds.coerceAtLeast(0L)
        val distance = if (pending.expectedPositionSeconds >= reported) {
            pending.expectedPositionSeconds - reported
        } else {
            reported - pending.expectedPositionSeconds
        }
        if (distance <= TOLERANCE_SECONDS) return RendererResumePositionAction.Confirmed
        if (pending.correctionInFlight) return RendererResumePositionAction.Wait
        if (pending.correctionsIssued >= MAX_CORRECTIONS) return RendererResumePositionAction.GiveUp
        return RendererResumePositionAction.Correct(pending.expectedPositionSeconds)
    }

    fun correctionStarted(pending: PendingRendererResumePosition): PendingRendererResumePosition =
        pending.copy(
            correctionsIssued = pending.correctionsIssued + 1,
            correctionInFlight = true,
        )

    fun correctionFinished(pending: PendingRendererResumePosition): PendingRendererResumePosition =
        pending.copy(correctionInFlight = false)
}
