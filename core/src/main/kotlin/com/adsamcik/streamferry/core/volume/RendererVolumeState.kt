package com.adsamcik.streamferry.core.volume

import kotlin.math.abs

/**
 * The last renderer volume together with whether it came from the renderer itself. A remembered
 * default is never a safe baseline for relative volume controls on a newly connected TV.
 */
data class RendererVolumeState(
    val level: Float = 1f,
    val isSynchronized: Boolean = false,
) {
    fun awaitRenderer(): RendererVolumeState = copy(isSynchronized = false)

    fun acceptReported(reported: Float?): RendererVolumeState =
        reported?.takeIf { isNormalizedVolume(it) }?.let { copy(level = it, isSynchronized = true) }
            ?: copy(isSynchronized = false)

    /**
     * Accept an immediate post-command read only when it actually reflects [expected]. Cast volume
     * commands are asynchronous, so its session getter can legitimately retain the prior cached value
     * until a later device-volume callback arrives.
     */
    fun acceptReportedIfMatching(
        expected: Float,
        reported: Float?,
        tolerance: Float = DEFAULT_CONFIRMATION_TOLERANCE,
    ): RendererVolumeState? {
        val normalizedExpected = expected.takeIf { isNormalizedVolume(it) } ?: return null
        val normalizedReported = reported?.takeIf { isNormalizedVolume(it) } ?: return null
        if (!tolerance.isFinite() || tolerance < 0f) return null
        return takeIf { abs(normalizedReported - normalizedExpected) <= tolerance }
            ?.copy(level = normalizedReported, isSynchronized = true)
    }

    fun acceptExplicit(requested: Float): RendererVolumeState =
        copy(level = requested.coerceIn(0f, 1f), isSynchronized = true)

    fun adjustedLevel(direction: Int, step: Float): Float? {
        if (!isSynchronized || direction == 0 || step <= 0f || step.isNaN() || step.isInfinite()) return null
        return (level + if (direction > 0) step else -step).coerceIn(0f, 1f)
    }

    private companion object {
        const val DEFAULT_CONFIRMATION_TOLERANCE = 0.015f

        fun isNormalizedVolume(value: Float): Boolean =
            !value.isNaN() && !value.isInfinite() && value in 0f..1f
    }
}
