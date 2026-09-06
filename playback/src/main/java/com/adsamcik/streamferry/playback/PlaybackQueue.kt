package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.UserSession

/** Immutable FIFO entry; repeated media retain independent identities and resume positions. */
data class PlaylistEntry(
    val entryId: Long,
    val item: MediaItem,
    val owner: UserSession? = null,
) {
    init {
        require(entryId > 0L) { "Playlist entry ids must be positive." }
    }

    fun canPlayWith(session: UserSession?): Boolean =
        item.sourceId != MediaSourceIds.REMOTE || (owner != null && owner == session)
}

data class PlaybackQueue(
    val entries: List<PlaylistEntry> = emptyList(),
) {
    val next: PlaylistEntry? get() = entries.firstOrNull()
    val isEmpty: Boolean get() = entries.isEmpty()
    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    fun enqueue(entryId: Long, item: MediaItem, owner: UserSession? = null): PlaybackQueue {
        require(entries.none { it.entryId == entryId }) { "Playlist entry id is already in use." }
        return copy(entries = entries + PlaylistEntry(entryId, item, owner))
    }

    fun remove(entryId: Long): PlaybackQueue {
        val updated = entries.filterNot { it.entryId == entryId }
        return if (updated.size == entries.size) this else copy(entries = updated)
    }

    fun clear(): PlaybackQueue = if (entries.isEmpty()) this else PlaybackQueue()
}
