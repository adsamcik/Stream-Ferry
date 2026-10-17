package com.adsamcik.streamferry.data.audio

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/** Selects an exact source track, including separate commentary tracks in the same language. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioLanguageTrackSelector(context: Context, private val chosen: LocalAudioTrack) : DefaultTrackSelector(context) {
    init {
        parameters = buildUponParameters()
            .setForceHighestSupportedBitrate(true)
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override fun selectAudioTrack(
        info: MappedTrackInfo,
        supports: Array<Array<IntArray>>,
        mixedMimeSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        data class Candidate(val renderer: Int, val group: Int, val track: Int, val format: Format)
        val candidates = buildList {
            for (renderer in 0 until info.rendererCount) {
                if (info.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
                val groups = info.getTrackGroups(renderer)
                for (group in 0 until groups.length) for (track in 0 until groups[group].length) {
                    add(Candidate(renderer, group, track, groups[group].getFormat(track)))
                }
            }
        }
        val index = findAudioTrack(candidates.map { it.format }, chosen)
        check(index >= 0) { "The selected audio track is no longer available. Choose another audio track." }
        val selected = candidates[index]
        check(RendererCapabilities.getFormatSupport(supports[selected.renderer][selected.group][selected.track]) == C.FORMAT_HANDLED) {
            "This phone cannot decode the selected audio track. Choose another audio track."
        }
        return Pair(ExoTrackSelection.Definition(info.getTrackGroups(selected.renderer)[selected.group], selected.track), selected.renderer)
    }
}

/** Progressive groups follow extractor track-id order. Ordinals are used only for formats without IDs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun findAudioTrack(formats: List<Format>, chosen: LocalAudioTrack): Int {
    if (chosen.formatId != null) return formats.indexOfFirst { it.id == chosen.formatId }
        .takeIf { formats.count { it.id == chosen.formatId } == 1 } ?: -1
    val format = formats.getOrNull(chosen.index) ?: return -1
    return chosen.index.takeIf {
        format.id == null && format.sampleMimeType == chosen.mimeType &&
            format.language?.takeUnless { it.isBlank() || it == "und" } == chosen.language && format.channelCount == chosen.channelCount &&
            format.sampleRate == chosen.sampleRate && format.roleFlags == chosen.roleFlags
    } ?: -1
}

internal val LocalAudioTrack.codecName: String?
    get() = when (mimeType) {
        "audio/mp4a-latm", "audio/aac" -> "aac"
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "dts"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/flac" -> "flac"
        "audio/raw" -> "pcm"
        else -> mimeType?.substringAfter('/')
    }
