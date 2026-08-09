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
    // Production uses Android AtomicFile. The seam keeps filesystem transformation tests deterministic
    // under Robolectric, whose Android-35 AtomicFile shadow does not commit the staged .new file.
    private val fileWriter: ((File, String) -> Unit)? = null,
    // Keep this last so existing LibraryCache(context) { clock } calls remain source-compatible.
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = File(context.applicationContext.filesDir, DIR)
    private val json = Json { ignoreUnknownKeys = true }
    private val legacyListSerializer = ListSerializer(MediaItem.serializer())
    private val mutex = Mutex()
    /** A remote request captures its sequence before touching Jellyfin; its late response cannot roll back a newer item. */
    @ConsistentCopyVisibility
    data class RemoteWriteToken internal constructor(
        internal val scope: String,
        internal val sequence: Long,
    )

    /** Identifies the one in-memory stale-response overlay installed by [patchItem]. */
    @ConsistentCopyVisibility
    data class ItemPatchToken internal constructor(
        internal val scope: String,
        internal val itemId: String,
        internal val generation: Long,
    )

    private data class ItemPatchOverlay(
        val token: ItemPatchToken,
        val transform: (MediaItem) -> MediaItem,
        // The highest request sequence that had already started when Jellyfin accepted the action. A
        // response beginning afterwards is authoritative and may retire this short-lived overlay.
        var confirmedAfterRemoteSequence: Long? = null,
    )

    /**
     * Process-lifetime stale-response guards for locally materialized watch-state changes. An overlay remains
     * mandatory while the server action is ambiguous. Once confirmed, only a remote request that began after
     * confirmation may retire it; older in-flight responses are still transformed. Per-item write sequences
     * then stop an even later old response from overwriting the reconciled cache/index.
     */
    private val itemPatchOverlays = mutableMapOf<String, MutableMap<String, ItemPatchOverlay>>()
    private val patchGenerationByScope = mutableMapOf<String, Long>()
    private val remoteWriteSequenceByScope = mutableMapOf<String, Long>()
    private val lastCommittedRemoteWriteByScopeAndItem = mutableMapOf<String, MutableMap<String, Long>>()

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
     * Capture a remote request before it starts. Pass the resulting [RemoteWriteToken] to [put] if that
     * request succeeds so cache writes can be ordered against watch-state patches and each other.
     */
    suspend fun beginRemoteWrite(scope: String): RemoteWriteToken = mutex.withLock {
        val sequence = (remoteWriteSequenceByScope[scope] ?: 0L) + 1L
        remoteWriteSequenceByScope[scope] = sequence
        RemoteWriteToken(scope = scope, sequence = sequence)
    }

    /** Compatibility path for locally seeded/test data, which has no in-flight remote request to order. */
    suspend fun put(scope: String, key: String, items: List<MediaItem>): List<MediaItem> =
        putInternal(scope = scope, key = key, items = items, remoteWrite = null)

    /** Store a remote response while preserving newer per-item responses and a relevant pending patch. */
    suspend fun put(remoteWrite: RemoteWriteToken, key: String, items: List<MediaItem>): List<MediaItem> =
        putInternal(scope = remoteWrite.scope, key = key, items = items, remoteWrite = remoteWrite)

    private suspend fun putInternal(
        scope: String,
        key: String,
        items: List<MediaItem>,
        remoteWrite: RemoteWriteToken?,
    ): List<MediaItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = scopeDir(scope)
            // If a newer response already established an item, retain that indexed version instead of
            // allowing an older request (possibly from another list key) to regress the shared search index.
            val orderedItems = remoteWrite?.let { reconcileOlderRemoteItems(scope, dir, it, items) } ?: items
            val patchedItems = applyItemPatchOverlays(scope, orderedItems, remoteWrite)
            val wrote = runCatching {
                writeRecord(fileFor(scope, key), StoredRecord(cachedAtMillis = clock(), items = patchedItems))
                val prior = indexForLookup(dir)
                writeIndex(dir, mergeById(prior, patchedItems))
                true
            }.getOrDefault(false)
            if (wrote && remoteWrite != null) {
                val committed = lastCommittedRemoteWriteByScopeAndItem.getOrPut(scope) { mutableMapOf() }
                patchedItems.forEach { item ->
                    if (remoteWrite.sequence >= (committed[item.id] ?: 0L)) {
                        committed[item.id] = remoteWrite.sequence
                    }
                }
            }
            // Return exactly what was stored so callers also cannot publish a stale in-flight response.
            patchedItems
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
            itemPatchOverlays.clear()
            patchGenerationByScope.clear()
            remoteWriteSequenceByScope.clear()
            lastCommittedRemoteWriteByScopeAndItem.clear()
            runCatching { root.deleteRecursively() }
            Unit
        }
    }

    private fun scopeDir(scope: String) = File(root, safe(scope))

    /**
     * Rewrite every cached representation of one item inside [scope], including the offline search index,
     * and install an in-memory guard for a response that was already in flight. The returned token must be
     * confirmed only after Jellyfin accepts the absolute action; a later authoritative response then retires
     * the guard naturally instead of pinning future playback progress forever.
     *
     * Unlike regular cache refreshes, a write failure is surfaced to the caller. A caller must not acknowledge
     * a durable watch-state journal entry while stale on-disk metadata could still resurrect old progress.
     */
    suspend fun patchItem(
        scope: String,
        itemId: String,
        transform: (MediaItem) -> MediaItem,
    ): ItemPatchToken = mutex.withLock {
        withContext(Dispatchers.IO) {
            val generation = (patchGenerationByScope[scope] ?: 0L) + 1L
            patchGenerationByScope[scope] = generation
            val token = ItemPatchToken(scope = scope, itemId = itemId, generation = generation)
            itemPatchOverlays.getOrPut(scope) { mutableMapOf() }[itemId] = ItemPatchOverlay(token, transform)

            val dir = scopeDir(scope)
            recordFiles(dir).forEach { file ->
                val record = readRecord(file) ?: return@forEach
                val patchedItems = applyItemPatchOverlays(scope, record.items)
                if (patchedItems != record.items) {
                    // A local watch-state edit is not a server refresh. Preserve the source record's age so
                    // stale-while-revalidate behaviour remains honest while offline.
                    writeRecord(file, StoredRecord(cachedAtMillis = record.cachedAtMillis, items = patchedItems))
                }
            }

            // The index can outlive individual browse files after an interrupted cleanup. Patch it
            // independently so index-only offline item lookup/search cannot resurrect old progress.
            val index = readIndex(dir)
            val patchedIndex = index?.let { applyItemPatchOverlays(scope, it) }
            if (patchedIndex != null && patchedIndex != index) writeIndex(dir, patchedIndex)
            token
        }
    }

    /** Permit only requests begun after this call to replace the matching in-memory patch. */
    suspend fun confirmItemPatch(token: ItemPatchToken): Boolean = mutex.withLock {
        val overlay = itemPatchOverlays[token.scope]?.get(token.itemId) ?: return@withLock false
        if (overlay.token != token) return@withLock false
        overlay.confirmedAfterRemoteSequence = remoteWriteSequenceByScope[token.scope] ?: 0L
        true
    }

    /** True while [token]'s stale-response guard remains installed; used to retire matching UI overlays safely. */
    suspend fun isItemPatchActive(token: ItemPatchToken): Boolean = mutex.withLock {
        itemPatchOverlays[token.scope]?.get(token.itemId)?.token == token
    }

    private fun reconcileOlderRemoteItems(
        scope: String,
        dir: File,
        remoteWrite: RemoteWriteToken,
        items: List<MediaItem>,
    ): List<MediaItem> {
        val committed = lastCommittedRemoteWriteByScopeAndItem[scope] ?: return items
        if (items.none { remoteWrite.sequence < (committed[it.id] ?: 0L) }) return items
        val indexed = indexForLookup(dir).associateBy { it.id }
        return items.map { item ->
            if (remoteWrite.sequence < (committed[item.id] ?: 0L)) indexed[item.id] ?: item else item
        }
    }

    private fun applyItemPatchOverlays(
        scope: String,
        items: List<MediaItem>,
        remoteWrite: RemoteWriteToken? = null,
    ): List<MediaItem> {
        val overlays = itemPatchOverlays[scope] ?: return items
        val retire = mutableListOf<String>()
        val patched = items.map { item ->
            val overlay = overlays[item.id] ?: return@map item
            val confirmationBarrier = overlay.confirmedAfterRemoteSequence
            if (remoteWrite != null && confirmationBarrier != null && remoteWrite.sequence > confirmationBarrier) {
                // This request began only after the server accepted the action. Its result is now the source
                // of truth (including a later real playback progress update), so retire the temporary guard.
                retire += item.id
                item
            } else {
                overlay.transform(item)
            }
        }
        retire.forEach { itemId -> overlays.remove(itemId) }
        if (overlays.isEmpty()) itemPatchOverlays.remove(scope)
        return patched
    }
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
        writeCacheFile(
            File(dir, INDEX_FILE),
            json.encodeToString(StoredIndex.serializer(), StoredIndex(items = items)),
        )
    }

    private fun writeRecord(file: File, record: StoredRecord) {
        writeCacheFile(file, json.encodeToString(StoredRecord.serializer(), record))
    }

    private fun writeCacheFile(file: File, text: String) {
        fileWriter?.invoke(file, text) ?: writeAtomically(file, text)
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
