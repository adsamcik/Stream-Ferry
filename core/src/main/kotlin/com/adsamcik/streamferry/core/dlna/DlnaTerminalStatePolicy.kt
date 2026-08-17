package com.adsamcik.streamferry.core.dlna

/**
 * Classifies an ambiguous DLNA terminal transport state.
 *
 * Unlike Cast, AVTransport does not say whether STOPPED/NO_MEDIA_PRESENT means that the item finished or
 * that somebody pressed Stop on the renderer. Treat it as completion only when the renderer made it near
 * the known end of the loaded item; otherwise it is an explicit/ambiguous stop and must not mark media
 * watched or trigger autoplay.
 */
object DlnaTerminalStatePolicy {

    enum class Outcome { NONE, COMPLETED, STOPPED }

    fun classify(
        everPlayed: Boolean,
        transportState: String?,
        furthestPositionSeconds: Long,
        durationSeconds: Long?,
    ): Outcome {
        if (!everPlayed || transportState !in TERMINAL_STATES) return Outcome.NONE
        val duration = durationSeconds?.takeIf { it > 0 } ?: return Outcome.STOPPED
        return if (furthestPositionSeconds >= completionThreshold(duration)) {
            Outcome.COMPLETED
        } else {
            Outcome.STOPPED
        }
    }

    /** Allow normal polling/renderer lag, but never infer completion earlier than the final 30 seconds. */
    internal fun completionThreshold(durationSeconds: Long): Long {
        val tolerance = (durationSeconds / 10).coerceIn(MIN_TOLERANCE_SECONDS, MAX_TOLERANCE_SECONDS)
        return (durationSeconds - tolerance).coerceAtLeast(0)
    }

    private val TERMINAL_STATES = setOf("STOPPED", "NO_MEDIA_PRESENT")
    private const val MIN_TOLERANCE_SECONDS = 2L
    private const val MAX_TOLERANCE_SECONDS = 30L
}
