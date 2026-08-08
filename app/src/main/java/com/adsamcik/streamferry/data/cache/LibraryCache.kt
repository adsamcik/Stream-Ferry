package com.adsamcik.streamferry.data.cache

import android.content.Context
import android.util.AtomicFile
import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Optional on-disk cache of the Jellyfin library **metadata** (titles/ids/years/overviews — no media
 * bytes, no token) so browsing works offline and paints instantly (§8). Stored in app-private internal
 * storage (sandboxed; excluded from backup by `data_extraction_rules`) and removed by "Delete all app
 * data". Keyed by a per-server-and-user [scope] so different accounts never mix.
 */
class LibraryCache(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val root = File(context.applicationContext.filesDir, DIR)
    private val json = Json { ignoreUnknownKeys = true }
    private val legacyListSerializer = ListSerializer(MediaItem.serializer())
    private val mutex = Mutex()

    /**
     * A cached list together with the instant at which it was successfully fetched. This is deliberately
     * separate from freshness policy: callers may still use an old record while a server is unavailable,
     * but can present its age honestly in the UI.
     */
    data class Record(
        val items: List<MediaItem>,
        val cachedAtMillis: Long,
    )

    /** Cached data plus advisory freshness for scroll-triggered refreshes. Stale data remains usable. */
    data class RecordState(
        val record: Record,
        val ageMillis: Long,
        val refreshRecommended: Boolean,
    )

    @Serializable
    private data class StoredRecord(
        val version: Int = CURRENT_VERSION,
        val cachedAtMillis: Long,
        val items: List<MediaItem>,
    )

    /** The per-scope materialized metadata index used for item lookup and offline search. */
    @Serializable
    private data class StoredIndex(
        val version: Int = CURRENT_VERSION,
        val items: List<MediaItem>,
    )

    /**
     * Replace one cached list and fold its items into the scope-wide search index. Existing indexed
     * items from other browsed lists are retained: a cached search should cover everything the user has
     * already visited, not only the most recently opened folder.
     */
    suspend fun put(scope: String, key: String, items: List<MediaItem>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = scopeDir(scope)
                writeAtomically(
                    fileFor(scope, key),
                    json.encodeToString(
                        StoredRecord.serializer(),
                        StoredRecord(cachedAtMillis = clock(), items = items),
                    ),
                )
                val prior = indexForLookup(dir)
                writeIndex(dir, mergeById(prior, items))
            }
            Unit
        }
    }

    /** Backwards-compatible list-only read for existing repository callers. */
    suspend fun get(scope: String, key: String): List<MediaItem>? = getRecord(scope, key)?.items

    /**
     * Read a timestamped cached list. Legacy cache files were a bare JSON [List] and remain readable;
     * their filesystem modification time becomes the best available cache timestamp.
     */
    suspend fun getRecord(scope: String, key: String): Record? = mutex.withLock {
        withContext(Dispatchers.IO) { readRecord(fileFor(scope, key)) }
    }

    /**
     * Reads a cached record whether fresh or stale. Refresh is advisory, so stale metadata remains
     * available as the offline fallback while a scrolling refresh is attempted.
     */
    suspend fun getRecordState(
        scope: String,
        key: String,
        refreshAfterMillis: Long,
    ): RecordState? = mutex.withLock {
        require(refreshAfterMillis >= 0L) { "refreshAfterMillis must be non-negative" }
        withContext(Dispatchers.IO) {
            readRecord(fileFor(scope, key))?.let { record ->
                val age = (clock() - record.cachedAtMillis).coerceAtLeast(0L)
                RecordState(record, age, age >= refreshAfterMillis)
            }
        }
    }

    /** Return a previously indexed item detail/summary without contacting Jellyfin. */
    suspend fun item(scope: String, itemId: String): MediaItem? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = scopeDir(scope)
            val indexed = indexForLookup(dir)
            indexed.firstOrNull { it.id == itemId }
        }
    }

    /**
     * Case-insensitive local search across the metadata that has already been cached for this scope.
     * The stable title/id ordering makes offline results deterministic even when folder lists were cached
     * in a different order.
     */
    suspend fun search(scope: String, query: String): List<MediaItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val needle = query.trim().lowercase(Locale.ROOT)
            if (needle.isEmpty()) return@withContext emptyList()
            val dir = scopeDir(scope)
            val indexed = indexForLookup(dir)
            indexed.asSequence()
                .filter { it.matches(needle) }
                .sortedWith(compareBy<MediaItem>({ it.title.lowercase(Locale.ROOT) }, { it.id }))
                .toList()
        }
    }

    /** True when this scope has at least one readable cached list (including a valid empty list). */
    suspend fun hasCachedData(scope: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = scopeDir(scope)
            if (!dir.isDirectory) return@withContext false
            if (recordFiles(dir).any { readRecord(it) != null }) return@withContext true
            // An intact index can still recover a cache after a list-file cleanup interrupted between
            // writes. It contains no secrets and is safe to use as the remaining offline metadata.
            readIndex(dir)?.isNotEmpty() == true
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { root.deleteRecursively() }
            Unit
        }
    }

    private fun scopeDir(scope: String) = File(root, safe(scope))

    private fun fileFor(scope: String, key: String) = File(scopeDir(scope), safe(key) + ".json")

    private fun readRecord(file: File): Record? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        runCatching { json.decodeFromString(StoredRecord.serializer(), text) }.getOrNull()?.let {
            return Record(items = it.items, cachedAtMillis = it.cachedAtMillis)
        }
        // Versions before timestamped records stored a bare JSON list. Preserve that data rather than
        // making an app upgrade erase offline browsing; file mtime is the only honest age available.
        return runCatching { json.decodeFromString(legacyListSerializer, text) }.getOrNull()?.let {
            Record(items = it, cachedAtMillis = file.lastModified().coerceAtLeast(0L))
        }
    }

    private fun readIndex(dir: File): List<MediaItem>? {
        val file = File(dir, INDEX_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(StoredIndex.serializer(), text) }.getOrNull()?.items
    }

    private fun indexForLookup(dir: File): List<MediaItem> {
        val index = readIndex(dir)
        val records = rebuildIndex(dir)
        if (index == null) {
            runCatching { writeIndex(dir, records) }
            return records
        }
        val reconciled = mergeById(index, records)
        if (reconciled != index) runCatching { writeIndex(dir, reconciled) }
        return reconciled
    }

    private fun rebuildIndex(dir: File): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        recordFiles(dir).sortedBy { it.name }.forEach { file ->
            readRecord(file)?.items?.forEach { item -> byId[item.id] = item }
        }
        return byId.values.toList()
    }

    private fun recordFiles(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.extension == "json" && file.name != INDEX_FILE }
            ?.toList()
            ?: emptyList()

    private fun mergeById(existing: List<MediaItem>, incoming: List<MediaItem>): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        existing.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    private fun writeIndex(dir: File, items: List<MediaItem>) {
        writeAtomically(
            File(dir, INDEX_FILE),
            json.encodeToString(StoredIndex.serializer(), StoredIndex(items = items)),
        )
    }

    /** AtomicFile preserves the previous complete JSON if a process dies during a cache update. */
    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            out = stream
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            out = null
        } catch (t: Throwable) {
            out?.let { atomic.failWrite(it) }
            throw t
        }
    }

    private fun MediaItem.matches(needle: String): Boolean =
        listOf(title, subtitle, overview, type)
            .any { value -> value?.lowercase(Locale.ROOT)?.contains(needle) == true }

    /**
     * A filesystem-safe, collision-resistant filename component. Filtering/truncating identities made
     * distinct server/user scopes collide; a SHA-256 digest keeps metadata caches isolated instead.
     */
    private fun safe(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val DIR = "libcache"
        private const val INDEX_FILE = "index.json"
        private const val CURRENT_VERSION = 1
        const val KEY_VIEWS = "views"
        const val KEY_RESUME = "resume"
        fun childrenKey(parentId: String) = "children_$parentId"
    }
}
