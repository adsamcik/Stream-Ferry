package com.adsamcik.streamferry.core.volume

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

    fun acceptExplicit(requested: Float): RendererVolumeState =
        copy(level = requested.coerceIn(0f, 1f), isSynchronized = true)

    fun adjustedLevel(direction: Int, step: Float): Float? {
        if (!isSynchronized || direction == 0 || step <= 0f || step.isNaN() || step.isInfinite()) return null
        return (level + if (direction > 0) step else -step).coerceIn(0f, 1f)
    }

    private companion object {
        fun isNormalizedVolume(value: Float): Boolean =
            !value.isNaN() && !value.isInfinite() && value in 0f..1f
    }
}