package com.adsamcik.streamferry.data.subtitle

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import java.io.Closeable

/** Times are on the original source timeline, including when a transcode starts at an offset. */
interface SubtitleCueSource : Closeable {
    fun cuesAt(sourceTimeUs: Long): List<Cue>
}

sealed interface LocalSubtitleSource {
    data class Embedded(val trackId: Int) : LocalSubtitleSource
    data class External(val uri: String, val mimeType: String) : LocalSubtitleSource
}

data class LocalSubtitleTrack(
    val index: Int,
    val language: String?,
    val label: String,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val source: LocalSubtitleSource,
    val unsupportedReason: String? = null,
)

/** Keeps overlapping captions; replacement tracks explicitly end their preceding cue group. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleCueTimeline(private val replace: Boolean) {
    private data class Entry(val start: Long, var end: Long, val cues: List<Cue>)
    private val entries = ArrayList<Entry>()

    fun add(cues: CuesWithTiming) {
        if (replace) {
            entries.filter { it.start <= cues.startTimeUs && it.end > cues.startTimeUs }
                .forEach { it.end = cues.startTimeUs }
        }
        val end = if (cues.durationUs == C.TIME_UNSET) Long.MAX_VALUE else cues.endTimeUs
        entries += Entry(cues.startTimeUs, end, cues.cues)
    }

    fun cuesAt(timeUs: Long): List<Cue> = entries.asSequence()
        .filter { timeUs >= it.start && timeUs < it.end }
        .flatMap { it.cues.asSequence() }.toList()

    fun discardBefore(timeUs: Long) {
        entries.removeAll { it.end <= timeUs }
    }
}
