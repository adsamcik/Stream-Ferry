package com.adsamcik.streamferry.core.hls

import com.adsamcik.streamferry.core.http.ByteRange
import kotlin.math.roundToLong

/** Bounded media-time metadata only. Playlist URLs are resolved by the provider and never persisted. */
data class HlsMediaSpan(val reference: String, val startMs: Long, val endMs: Long, val byteRange: ByteRange?)

object HlsDeliveryTimeline {
    /** Full-timeline VOD/event playlists only; never pretend a sliding/live window starts at media zero. */
    fun parse(body: String): List<HlsMediaSpan> {
        if (body.length > 2 * 1024 * 1024 || !body.trimStart().startsWith("#EXTM3U")) return emptyList()
        if (body.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-I-FRAMES-ONLY") }) return emptyList()
        val sequence = body.lineSequence().firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
        if (sequence != null && sequence.substringAfter(':').trim().toLongOrNull() != 0L) return emptyList()
        val result = mutableListOf<HlsMediaSpan>()
        var positionMs = 0.0
        var durationMs: Double? = null
        var rangeText: String? = null
        var previousRangeEnd: Long? = null
        var previousReference: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    if (durationMs != null) return emptyList()
                    durationMs = line.substringAfter(':').substringBefore(',').toDoubleOrNull()?.times(1000)
                    if (durationMs == null || !durationMs.isFinite() || durationMs <= 0 || durationMs > 3_600_000) return emptyList()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> rangeText = line.substringAfter(':')
                line == "#EXT-X-GAP" || line.startsWith("#EXT-X-DISCONTINUITY") -> return emptyList()
                line.isNotEmpty() && !line.startsWith('#') -> {
                    val duration = durationMs ?: return emptyList()
                    val range = rangeText?.let { text ->
                        val length = text.substringBefore('@').toLongOrNull() ?: return emptyList()
                        val start = if ('@' in text) text.substringAfter('@').toLongOrNull() else
                            previousRangeEnd?.takeIf { previousReference == line }?.plus(1)
                        if (start == null || start < 0 || length <= 0 || length > Long.MAX_VALUE - start) return emptyList()
                        ByteRange(start, start + length - 1)
                    }
                    val end = positionMs + duration
                    if (end > 7 * 24 * 3_600_000.0 || result.size >= 20_000) return emptyList()
                    result += HlsMediaSpan(line, positionMs.roundToLong(), end.roundToLong(), range)
                    positionMs = end
                    previousReference = line
                    previousRangeEnd = range?.endInclusive
                    durationMs = null
                    rangeText = null
                }
            }
        }
        return if (durationMs == null) result else emptyList()
    }
}
