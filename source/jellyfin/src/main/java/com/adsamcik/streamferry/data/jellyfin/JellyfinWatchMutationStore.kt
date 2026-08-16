package com.adsamcik.streamferry.data.jellyfin

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Absolute, idempotent Jellyfin user-data operations that are safe to replay after a crash. */
enum class JellyfinWatchMutationKind {
    MARK_PLAYED,
    MARK_UNPLAYED,
    RESET_PROGRESS,
}

/**
 * A write-ahead watch-state action scoped to one Jellyfin account and library item. [operationId] makes
 * acknowledgement conditional: a delayed completion can never erase a newer intent for the same item.
 */
data class JellyfinWatchMutation(
    val operationId: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val kind: JellyfinWatchMutationKind,
    val createdAtMillis: Long,
) {
    fun isStructurallyValid(): Boolean =
        operationId.isNotBlank() && serverId.isNotBlank() && userId.isNotBlank() && itemId.isNotBlank() &&
            createdAtMillis >= 0L
}

/**
 * Tiny no-backup write-ahead journal for manual watched/unwatched/reset intents. The journal serves two
 * roles: it retries an ambiguous network action once the exact account is verified again, and it suppresses
 * old crash checkpoints until that absolute action is acknowledged. It stores identities only — no token,
 * URL, media path, or renderer data.
 */
class JellyfinWatchMutationStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    private val lock = Any()
    private var entries: List<JellyfinWatchMutation> = load()

    fun pendingFor(serverId: String, userId: String): List<JellyfinWatchMutation> = synchronized(lock) {
        entries.filter { it.serverId == serverId && it.userId == userId }
    }

    fun pendingFor(serverId: String, userId: String, itemId: String): JellyfinWatchMutation? = synchronized(lock) {
        entries.lastOrNull { it.serverId == serverId && it.userId == userId && it.itemId == itemId }
    }

    /** Persist the latest intent for this exact account/item before any remote request begins. */
    fun put(mutation: JellyfinWatchMutation) = synchronized(lock) {
        require(mutation.isStructurallyValid()) { "Invalid Jellyfin watch-state mutation." }
        val next = (entries.filterNot {
            it.serverId == mutation.serverId && it.userId == mutation.userId && it.itemId == mutation.itemId
        } + mutation).sortedBy { it.createdAtMillis }
        write(next)
        entries = next
    }

    /** Remove only the operation that was actually acknowledged; a newer intent must survive. */
    fun acknowledgeIfCurrent(mutation: JellyfinWatchMutation): Boolean = synchronized(lock) {
        val current = pendingFor(mutation.serverId, mutation.userId, mutation.itemId)
        if (current?.operationId != mutation.operationId) return@synchronized false
        val next = entries.filterNot {
            it.serverId == mutation.serverId && it.userId == mutation.userId && it.itemId == mutation.itemId &&
                it.operationId == mutation.operationId
        }
        write(next)
        entries = next
        true
    }

    /** Forgetting a server also removes its non-secret pending user-data intents. */
    fun removeServer(serverId: String) = synchronized(lock) {
        val next = entries.filterNot { it.serverId == serverId }
        if (next == entries) return@synchronized
        write(next)
        entries = next
    }

    fun clear() = synchronized(lock) {
        atomicFile.delete()
        entries = emptyList()
    }

    private fun load(): List<JellyfinWatchMutation> {
        if (!atomicFile.baseFile.isFile) return emptyList()
        val decoded = runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                JellyfinWatchMutationJsonCodec.decode(JSONObject(reader.readText()))
            }
        }.getOrNull()?.filter { it.isStructurallyValid() }
        if (decoded == null) {
            atomicFile.delete()
            return emptyList()
        }
        // Keep at most one newest intent per item even if an old pre-release build wrote duplicates.
        return decoded.groupBy { Triple(it.serverId, it.userId, it.itemId) }
            .values.mapNotNull { itemEntries -> itemEntries.maxByOrNull { it.createdAtMillis } }
            .sortedBy { it.createdAtMillis }
    }

    private fun write(next: List<JellyfinWatchMutation>) {
        if (next.isEmpty()) {
            atomicFile.delete()
            return
        }
        val stream = atomicFile.startWrite()
        try {
            stream.write(JellyfinWatchMutationJsonCodec.encode(next).toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "jellyfin_watch_mutations_v1.json"
    }
}

/** Versioned JSON boundary; malformed documents are discarded by [JellyfinWatchMutationStore]. */
internal object JellyfinWatchMutationJsonCodec {
    private const val VERSION = 1

    fun encode(entries: List<JellyfinWatchMutation>): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("operationId", entry.operationId)
                    put("serverId", entry.serverId)
                    put("userId", entry.userId)
                    put("itemId", entry.itemId)
                    put("kind", entry.kind.name)
                    put("createdAtMillis", entry.createdAtMillis)
                })
            }
        })
    }

    fun decode(document: JSONObject): List<JellyfinWatchMutation>? {
        if (document.optInt("version", -1) != VERSION) return null
        val rawEntries = document.optJSONArray("entries") ?: return null
        // AtomicFile makes a torn whole document unlikely, but preserve every valid intent if a hand-edited
        // or older document contains one malformed entry. Dropping a pending action silently is worse than
        // skipping only the undecodable record.
        return buildList {
            for (index in 0 until rawEntries.length()) {
                val entry = rawEntries.optJSONObject(index) ?: continue
                val mutation = runCatching {
                    JellyfinWatchMutation(
                        operationId = entry.getString("operationId"),
                        serverId = entry.getString("serverId"),
                        userId = entry.getString("userId"),
                        itemId = entry.getString("itemId"),
                        kind = JellyfinWatchMutationKind.valueOf(entry.getString("kind")),
                        createdAtMillis = entry.getLong("createdAtMillis"),
                    )
                }.getOrNull() ?: continue
                if (mutation.isStructurallyValid()) add(mutation)
            }
        }
    }
}