package com.adsamcik.streamferry.playback

/** Parser for the exact-position dialog. Accepts seconds, MM:SS, or HH:MM:SS. */
object PlaybackTimecode {
    fun parse(input: String, durationSeconds: Long): Long? {
        if (durationSeconds <= 0L) return null
        val parts = input.trim().split(':')
        if (parts.size !in 1..3 || parts.any { it.isBlank() || it.any { char -> !char.isDigit() } }) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        val total = when (values.size) {
            1 -> values[0]
            2 -> {
                if (values[1] !in 0L..59L) return null
                safeTotal(values[0], 60L, values[1]) ?: return null
            }
            else -> {
                if (values[1] !in 0L..59L || values[2] !in 0L..59L) return null
                val hours = safeTotal(values[0], 3_600L, 0L) ?: return null
                val minutes = safeTotal(values[1], 60L, values[2]) ?: return null
                runCatching { Math.addExact(hours, minutes) }.getOrNull() ?: return null
            }
        }
        return total.takeIf { it in 0L..durationSeconds }
    }

    private fun safeTotal(major: Long, multiplier: Long, minor: Long): Long? = runCatching {
        Math.addExact(Math.multiplyExact(major, multiplier), minor)
    }.getOrNull()
}
