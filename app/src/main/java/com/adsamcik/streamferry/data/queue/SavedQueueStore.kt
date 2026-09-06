package com.adsamcik.streamferry.data.queue

import com.adsamcik.streamferry.data.download.CrashSafeTextFile
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.playback.PlaybackQueue
import com.adsamcik.streamferry.playback.PlaylistEntry
import com.adsamcik.streamferry.playback.QueueContents
import com.adsamcik.streamferry.playback.SavedPlaybackQueue
import com.adsamcik.streamferry.playback.SavedQueueLibrary
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private, no-backup storage. Only stable media metadata is stored, never live playback URLs. */
class SavedQueueStore(private val file: File) {
    fun read(): SavedQueueLibrary = CrashSafeTextFile.readRecovering(file, SavedQueueCodec::decode) ?: SavedQueueLibrary()
    fun write(library: SavedQueueLibrary) = CrashSafeTextFile.write(file, SavedQueueCodec.encode(library))
    fun clear() = CrashSafeTextFile.delete(file)
}

internal object SavedQueueCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Entry(
        val id: Long, val item: MediaItem, val serverId: String? = null, val userId: String? = null,
    ) {
        fun domain(): PlaylistEntry {
            require(id > 0 && id < Long.MAX_VALUE && item.id.isNotBlank() && !item.isFolder)
            require(item.sourceId in setOf(MediaSourceIds.LOCAL, MediaSourceIds.REMOTE))
            val owner = if (item.sourceId == MediaSourceIds.REMOTE) {
                require(!serverId.isNullOrBlank() && !userId.isNullOrBlank())
                UserSession(userId, serverId)
            } else null
            return PlaylistEntry(id, item, owner)
        }
    }
    @Serializable private data class Contents(val entries: List<Entry> = emptyList(), val current: Entry? = null) {
        fun domain(): QueueContents {
            val decoded = entries.map { it.domain() }
            val active = current?.domain()
            val ids = decoded.map { it.entryId } + listOfNotNull(active?.entryId)
            require(ids.distinct().size == ids.size)
            return QueueContents(PlaybackQueue(decoded), active)
        }
    }
    @Serializable private data class Saved(val id: String, val name: String, val contents: Contents)
    @Serializable private data class Document(
        val version: Int = 1, val working: Contents, val saved: List<Saved>, val activeId: String? = null,
    )

    fun encode(library: SavedQueueLibrary): String = json.encodeToString(Document(
        working = library.working.stored(),
        saved = library.saved.map { Saved(it.id, it.name, it.contents.stored()) },
        activeId = library.activeId,
    ))

    fun decode(text: String): SavedQueueLibrary {
        val document = json.decodeFromString<Document>(text)
        require(document.version == 1)
        val saved = document.saved.map {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 80)
            SavedPlaybackQueue(it.id, it.name, it.contents.domain())
        }
        require(saved.map { it.id }.distinct().size == saved.size)
        require(document.activeId == null || saved.any { it.id == document.activeId })
        return SavedQueueLibrary(document.working.domain(), saved, document.activeId)
    }

    private fun PlaylistEntry.stored() = Entry(
        entryId, item.copy(overview = null, artwork = null, chapters = emptyList()), owner?.serverId, owner?.userId,
    )
    private fun QueueContents.stored() = Contents(upNext.entries.map { it.stored() }, current?.stored())
}
