package com.adsamcik.streamferry.data.download

import android.content.Context
import com.adsamcik.streamferry.core.download.DownloadPaths
import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Identifies the Jellyfin account that owns a downloaded item; it never contains a token or URL. */
@Serializable
data class DownloadOwner(
    val serverId: String,
    val userId: String,
)

/** A completed offline download (metadata only — never the token). */
@Serializable
data class DownloadEntry(
    val itemId: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val container: String? = null,
    val sizeBytes: Long = 0,
    val runtimeSeconds: Long? = null,
    val downloadedAtMillis: Long = 0,
    val qualityLabel: String? = null,
    /** Null only for indexes written by older app builds. */
    val owner: DownloadOwner? = null,
    /** Full non-secret gallery metadata, retained so this download remains discoverable offline. */
    val mediaItem: MediaItem? = null,
)

/**
 * App-private storage for the optional offline-download feature (§5 download exception): the media
 * files plus a small JSON index. Lives in internal storage (sandboxed; excluded from backup) and is
 * removed by "Delete all app data". The on-disk filename is derived only from the (alphanumeric) item
 * id via [DownloadPaths], so a media title can never influence the path.
 */
class DownloadStore private constructor(filesDir: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesDir, DIR)
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(DownloadEntry.serializer())
    private val mutex = Mutex()

    fun fileFor(entry: DownloadEntry): File = File(dir, entry.fileName)

    fun partFileFor(itemId: String, owner: DownloadOwner? = null): File =
        File(dir, DownloadIdentity(owner, itemId).fileStem + ".part")

    /** Sidecar holding the upstream validator (ETag/Last-Modified) for a `.part`, used for safe resume. */
    fun partMetaFileFor(itemId: String, owner: DownloadOwner? = null): File =
        File(dir, DownloadIdentity(owner, itemId).fileStem + ".partmeta")

    /** Every completed entry, retained for legacy callers that have no account scope yet. */
    suspend fun list(): List<DownloadEntry> = listInternal(owner = null, filterByOwner = false)

    /** Completed entries belonging to exactly [owner] (or the ownerless legacy scope when null). */
    suspend fun listForOwner(owner: DownloadOwner?): List<DownloadEntry> = listInternal(owner, filterByOwner = true)

    /**
     * Playable completed entries for one authenticated Jellyfin account. This is the account-scoped
     * API for offline gallery/search consumers; entries with missing or corrupt media bytes are pruned.
     */
    suspend fun list(owner: DownloadOwner): List<DownloadEntry> = listForOwner(owner)

    suspend fun get(itemId: String, owner: DownloadOwner? = null): DownloadEntry? =
        listForOwner(owner).firstOrNull { it.itemId == itemId }

    /** Account-scoped form of [get], kept in owner-first order for repository consumers. */
    suspend fun get(owner: DownloadOwner, itemId: String): DownloadEntry? = get(itemId, owner)

    suspend fun upsert(entry: DownloadEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val updated = (loadPlayableUnlocked().filterNot { it.identity == entry.identity } + entry)
                .sortedBy { it.title.lowercase() }
            indexFile.writeText(json.encodeToString(serializer, updated))
        }
    }

    suspend fun remove(itemId: String, owner: DownloadOwner? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = loadPlayableUnlocked()
            val identity = DownloadIdentity(owner, itemId)
            current.firstOrNull { it.identity == identity }?.let { runCatching { fileFor(it).delete() } }
            runCatching { partFileFor(itemId, owner).delete() }
            runCatching { partMetaFileFor(itemId, owner).delete() }
            indexFile.writeText(json.encodeToString(serializer, current.filterNot { it.identity == identity }))
        }
    }

    /** Account-scoped form of [remove], which can never affect another server/user's copy. */
    suspend fun remove(owner: DownloadOwner, itemId: String) = remove(itemId, owner)

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() }; Unit }
    }

    private suspend fun listInternal(owner: DownloadOwner?, filterByOwner: Boolean): List<DownloadEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val playable = loadPlayableUnlocked()
            if (filterByOwner) playable.filter { it.owner == owner } else playable
        }
    }

    private fun loadUnlocked(): List<DownloadEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching { json.decodeFromString(serializer, indexFile.readText()) }.getOrDefault(emptyList())
    }

    /**
     * An index entry is completed only while its bytes are present and readable. Pruning broken entries
     * here prevents downstream UI from advertising a copy that can only fail later as a proxy 404.
     */
    private fun loadPlayableUnlocked(): List<DownloadEntry> {
        val current = loadUnlocked()
        val playable = current.filter(::hasPlayableFile)
        if (playable.size != current.size) {
            // The entry is already omitted even if a transient rewrite failure prevents persistence; the
            // next read will retry cleanup.
            runCatching { indexFile.writeText(json.encodeToString(serializer, playable)) }
        }
        return playable
    }

    private fun hasPlayableFile(entry: DownloadEntry): Boolean {
        val file = fileFor(entry)
        val length = file.length()
        return file.isFile && file.canRead() && length > 0L &&
            (entry.sizeBytes <= 0L || length == entry.sizeBytes)
    }

    companion object {
        private const val DIR = "downloads"

        /** JVM test seam; production callers must use the application [Context] constructor. */
        internal fun forFilesDir(filesDir: File): DownloadStore = DownloadStore(filesDir)
    }
}
