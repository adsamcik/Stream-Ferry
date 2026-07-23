package com.videobridge.data.download

import android.content.Context
import com.videobridge.core.download.DownloadPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

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
)

/**
 * App-private storage for the optional offline-download feature (§5 download exception): the media
 * files plus a small JSON index. Lives in internal storage (sandboxed; excluded from backup) and is
 * removed by "Delete all app data". The on-disk filename is derived only from the (alphanumeric) item
 * id via [DownloadPaths], so a media title can never influence the path.
 */
class DownloadStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR)
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(DownloadEntry.serializer())
    private val mutex = Mutex()

    fun fileFor(entry: DownloadEntry): File = File(dir, entry.fileName)

    fun partFileFor(itemId: String): File = File(dir, DownloadPaths.safeBaseName(itemId) + ".part")

    /** Sidecar holding the upstream validator (ETag/Last-Modified) for a `.part`, used for safe resume. */
    fun partMetaFileFor(itemId: String): File = File(dir, DownloadPaths.safeBaseName(itemId) + ".partmeta")

    suspend fun list(): List<DownloadEntry> = mutex.withLock {
        withContext(Dispatchers.IO) { loadUnlocked() }
    }

    suspend fun get(itemId: String): DownloadEntry? = list().firstOrNull { it.itemId == itemId }

    suspend fun upsert(entry: DownloadEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val updated = (loadUnlocked().filterNot { it.itemId == entry.itemId } + entry).sortedBy { it.title.lowercase() }
            indexFile.writeText(json.encodeToString(serializer, updated))
        }
    }

    suspend fun remove(itemId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = loadUnlocked()
            current.firstOrNull { it.itemId == itemId }?.let { runCatching { fileFor(it).delete() } }
            runCatching { partFileFor(itemId).delete() }
            runCatching { partMetaFileFor(itemId).delete() }
            indexFile.writeText(json.encodeToString(serializer, current.filterNot { it.itemId == itemId }))
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() }; Unit }
    }

    private fun loadUnlocked(): List<DownloadEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching { json.decodeFromString(serializer, indexFile.readText()) }.getOrDefault(emptyList())
    }

    private companion object { const val DIR = "downloads" }
}
