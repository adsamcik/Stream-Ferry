package com.videobridge.data.download

sealed interface DownloadFormat {
    val label: String

    data object Original : DownloadFormat {
        override val label = "Original quality"
    }

    data class Transcode(
        override val label: String,
        val container: String,
        val videoCodec: String,
        val audioCodec: String,
        val maxBitrateBps: Long,
    ) : DownloadFormat

    companion object {
        val PRESETS: List<DownloadFormat> = listOf(
            Original,
            Transcode("MP4 · 1080p", "mp4", "h264", "aac", 8_000_000),
            Transcode("MP4 · 720p", "mp4", "h264", "aac", 4_000_000),
            Transcode("MP4 · 480p", "mp4", "h264", "aac", 2_000_000),
        )
    }
}
