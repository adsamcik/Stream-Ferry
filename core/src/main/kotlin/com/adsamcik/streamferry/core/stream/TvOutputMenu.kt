package com.adsamcik.streamferry.core.stream

/** One selectable TV transcode format. A null [codec] returns to automatic negotiation/direct play. */
data class TvOutputFormatOption(
    val codec: String?,
    val label: String,
    val isSelected: Boolean,
)

/** One selectable TV output-height cap. A null [heightPx] returns to the saved automatic cap. */
data class TvOutputResolutionOption(
    val heightPx: Int?,
    val label: String,
    val isSelected: Boolean,
)

/**
 * Pure policy for the live TV-output pickers. Auto remains a real option even when the TV has only
 * one safe forced-transcode codec: Auto may direct-play, while choosing that codec forces conversion.
 */
object TvOutputMenu {
    private val RESOLUTION_HEIGHTS = listOf(2160, 1080, 720, 480)

    fun formatOptions(
        availableVideoCodecs: List<String>,
        preferredVideoCodec: String?,
    ): List<TvOutputFormatOption> {
        val codecs = availableVideoCodecs
            .mapNotNull(::canonicalCodec)
            .distinct()
        val selected = canonicalCodec(preferredVideoCodec)?.takeIf { it in codecs }
        return listOf(
            TvOutputFormatOption(codec = null, label = "Auto", isSelected = selected == null),
        ) + codecs.map { codec ->
            TvOutputFormatOption(codec, formatLabel(codec), isSelected = codec == selected)
        }
    }

    fun resolutionOptions(
        automaticMaxHeightPx: Int,
        manualMaxHeightPx: Int?,
    ): List<TvOutputResolutionOption> {
        val selected = manualMaxHeightPx?.takeIf { it in RESOLUTION_HEIGHTS }
        val autoLabel = automaticMaxHeightPx.takeIf { it > 0 }
            ?.let { "Auto (${if (it == 2160) "4K" else "${it}p"})" }
            ?: "Auto"
        return listOf(
            TvOutputResolutionOption(heightPx = null, label = autoLabel, isSelected = selected == null),
        ) + RESOLUTION_HEIGHTS.map { height ->
            TvOutputResolutionOption(height, resolutionLabel(height), isSelected = height == selected)
        }
    }

    fun formatLabel(codec: String): String = when (canonicalCodec(codec) ?: codec.lowercase()) {
        "h264" -> "H.264"
        "hevc" -> "HEVC"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        else -> codec.uppercase()
    }

    private fun canonicalCodec(codec: String?): String? = when (codec?.trim()?.lowercase()?.takeIf(String::isNotEmpty)) {
        "h264", "avc", "avc1" -> "h264"
        "hevc", "h265", "hvc1" -> "hevc"
        "vp9", "vp09" -> "vp9"
        "av1", "av01" -> "av1"
        null -> null
        else -> codec.trim().lowercase()
    }

    private fun resolutionLabel(heightPx: Int): String =
        if (heightPx == 2160) "4K (2160p)" else "${heightPx}p"
}
