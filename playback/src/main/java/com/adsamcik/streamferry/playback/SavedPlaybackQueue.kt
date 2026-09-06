package com.adsamcik.streamferry.playback

/** The current entry survives Stop and process death, until it finishes or is explicitly skipped. */
data class QueueContents(
    val upNext: PlaybackQueue = PlaybackQueue(),
    val current: PlaylistEntry? = null,
) {
    val resumeEntry: PlaylistEntry? get() = current ?: upNext.next
    val size: Int get() = upNext.entries.size + if (current == null) 0 else 1

    fun started(entry: PlaylistEntry): QueueContents = copy(
        upNext = upNext.remove(entry.entryId), current = entry,
    )

    fun finished(entryId: Long): QueueContents =
        if (current?.entryId == entryId) copy(current = null) else this

    fun checkpoint(entryId: Long, positionSeconds: Long): QueueContents =
        if (current?.entryId == entryId) copy(
            current = current.copy(item = current.item.copy(resumePositionSeconds = positionSeconds.coerceAtLeast(0))),
        ) else this
}

data class SavedPlaybackQueue(val id: String, val name: String, val contents: QueueContents)

/** Working queue is always durable. Saving a name links subsequent progress/edits to that queue. */
data class SavedQueueLibrary(
    val working: QueueContents = QueueContents(),
    val saved: List<SavedPlaybackQueue> = emptyList(),
    val activeId: String? = null,
) {
    val activeName: String? get() = saved.firstOrNull { it.id == activeId }?.name
    val maxEntryId: Long get() = (saved.map { it.contents } + working)
        .flatMap { it.upNext.entries + listOfNotNull(it.current) }.maxOfOrNull { it.entryId } ?: 0L

    fun updateWorking(contents: QueueContents): SavedQueueLibrary = copy(
        working = contents,
        saved = saved.map { if (it.id == activeId) it.copy(contents = contents) else it },
    )

    fun save(id: String, name: String): SavedQueueLibrary {
        require(name.trim().isNotEmpty() && name.trim().length <= 80) { "Use a queue name between 1 and 80 characters." }
        require(working.size > 0) { "Add an item before saving a queue." }
        return copy(saved = saved.filterNot { it.id == id } + SavedPlaybackQueue(id, name.trim(), working), activeId = id)
    }

    fun load(id: String): SavedQueueLibrary = saved.firstOrNull { it.id == id }
        ?.let { copy(working = it.contents, activeId = id) } ?: this

    fun delete(id: String): SavedQueueLibrary = copy(
        saved = saved.filterNot { it.id == id }, activeId = activeId?.takeUnless { it == id },
    )

    fun newQueue(): SavedQueueLibrary = copy(working = QueueContents(), activeId = null)
}
