package com.adsamcik.streamferry.data.proxy

import com.adsamcik.streamferry.core.hls.HlsDeliveryTimeline
import com.adsamcik.streamferry.core.hls.HlsMediaSpan
import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.resume.Mp4DeliveryTracker
import com.adsamcik.streamferry.core.resume.DeliveredMediaSpan
import java.util.TreeMap

data class MediaDelivery(val sessionId: String, val epoch: Long, val startMs: Long, val endMs: Long)

/** In-memory, session-scoped metadata. Never interpret HTTP requests or partial bodies as playback. */
internal class ProxyDeliveryTracker {
    private data class Media(val spans: List<HlsMediaSpan>, val group: String, val audio: Boolean)
    private val media = mutableMapOf<String, Media>()
    private val primaryPlaylists = mutableMapOf<String, String>()
    private val audioPlaylists = mutableMapOf<String, String>()
    private val videoDelivered = mutableMapOf<String, TreeMap<Long, Long>>()
    private val audioDelivered = mutableMapOf<String, TreeMap<Long, Long>>()
    private var hasMaster = false
    private var epoch = 0L
    private val mp4 = Mp4DeliveryTracker()

    @Synchronized fun epoch() = epoch
    @Synchronized fun renew(): Long {
        mp4.resetDelivery()
        videoDelivered.clear()
        audioDelivered.clear()
        return ++epoch
    }

    @Synchronized fun written(offset: Long, bytes: ByteArray, length: Int, capturedEpoch: Long): List<DeliveredMediaSpan> =
        if (epoch != capturedEpoch) emptyList() else mp4.written(offset, bytes, length)

    @Synchronized fun playlist(id: String?, body: String, resolved: Map<String, String>) {
        if (body.length > 2 * 1024 * 1024) return
        val audioGroups = mutableSetOf<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attributes = attributes(line)
            if (attributes["TYPE"] != "AUDIO") continue
            val group = attributes["GROUP-ID"] ?: continue
            val uri = attributes["URI"] ?: continue // In-band audio needs no separate delivery.
            audioGroups += group
            resolved[uri]?.let { if (audioPlaylists.size < 64) audioPlaylists[it] = group }
        }
        var variantGroup: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                hasMaster = true
                variantGroup = attributes(line)["AUDIO"]?.takeIf { it in audioGroups }.orEmpty()
            } else if (variantGroup != null && line.isNotEmpty() && !line.startsWith('#')) {
                resolved[line]?.let { if (primaryPlaylists.size < 64) primaryPlaylists[it] = variantGroup }
                variantGroup = null
            }
        }
        if (hasMaster && id !in primaryPlaylists && id !in audioPlaylists) return
        val audio = id in audioPlaylists
        val group = (if (audio) audioPlaylists[id] else primaryPlaylists[id]).orEmpty()
        val spans = HlsDeliveryTimeline.parse(body)
        // Subtitles, init segments and keys cannot provide video/audio delivery evidence.
        if (spans.any { it.reference.substringBefore('?').endsWith(".vtt", true) }) return
        for ((reference, list) in spans.groupBy { it.reference }) {
            val resourceId = resolved[reference] ?: continue
            if (media.size >= 20_000 && resourceId !in media) continue
            val previous = media[resourceId]
            val next = Media(list, group, audio)
            // Ambiguous reuse of one resource at different media times cannot provide delivery evidence.
            media[resourceId] = if (previous == null || previous == next) next else next.copy(spans = emptyList())
        }
    }

    @Synchronized fun completed(id: String, requestedRange: ByteRange?, capturedEpoch: Long): List<DeliveredMediaSpan> {
        if (epoch != capturedEpoch) return emptyList()
        val resource = media[id] ?: return emptyList()
        val spans = resource.spans.filter { it.byteRange == requestedRange }
        if (spans.isEmpty()) return emptyList()
        val map = (if (resource.audio) audioDelivered else videoDelivered).getOrPut(resource.group) { TreeMap() }
        spans.forEach { merge(map, it.startMs, it.endMs) }
        val video = videoDelivered[resource.group] ?: return emptyList()
        if (resource.group.isEmpty()) return video.map { DeliveredMediaSpan(it.key, it.value) }
        val audio = audioDelivered[resource.group] ?: return emptyList()
        return video.flatMap { (start, end) ->
            val result = mutableListOf<DeliveredMediaSpan>()
            var candidate = audio.floorEntry(start)?.takeIf { it.value > start } ?: audio.ceilingEntry(start)
            while (candidate != null && candidate.key < end) {
                val finish = minOf(end, candidate.value)
                if (finish > maxOf(start, candidate.key)) result += DeliveredMediaSpan(maxOf(start, candidate.key), finish)
                candidate = audio.higherEntry(candidate.key)
            }
            result
        }
    }

    private fun merge(map: TreeMap<Long, Long>, begin: Long, finish: Long) {
        var start = begin; var end = finish
        map.floorEntry(start)?.takeIf { it.value >= start }?.let {
            start = it.key; end = maxOf(end, it.value); map.remove(it.key)
        }
        var next = map.ceilingEntry(start)
        while (next != null && next.key <= end) {
            end = maxOf(end, next.value); map.remove(next.key); next = map.ceilingEntry(start)
        }
        map[start] = end
        while (map.size > 512) map.pollFirstEntry()
    }

    private fun attributes(line: String): Map<String, String> = ATTRIBUTE.findAll(line.substringAfter(':')).associate {
        it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
    }

    companion object { private val ATTRIBUTE = Regex("(?:^|,)([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)") }
}
