package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.MediaItem

/** Immutable FIFO playlist for one app playback session. */
data class PlaylistEntry(
    val entryId: Long,
    val item: MediaItem,
) {
    init {
        require(entryId > 0L) { "Playlist entry ids must be positive." }
    }
}

data class PlaybackQueue(
    val entries: List<PlaylistEntry> = emptyList(),
) {
    val next: PlaylistEntry? get() = entries.firstOrNull()
    val isEmpty: Boolean get() = entries.isEmpty()
    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    fun enqueue(entryId: Long, item: MediaItem): PlaybackQueue {
        require(entries.none { it.entryId == entryId }) { "Playlist entry id is already in use." }
        return copy(entries = entries + PlaylistEntry(entryId, item))
    }

    fun remove(entryId: Long): PlaybackQueue {
        val updated = entries.filterNot { it.entryId == entryId }
        return if (updated.size == entries.size) this else copy(entries = updated)
    }

    fun clear(): PlaybackQueue = if (entries.isEmpty()) this else PlaybackQueue()
}
