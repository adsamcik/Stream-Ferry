package com.adsamcik.streamferry.data.download

import android.content.Context
import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A download the user asked for that has not finished yet. Persisted so an in-flight download can be
 * **resumed automatically** after the process is killed in the background (e.g. aggressive OEM battery
 * management) or after the foreground service is restarted by the system.
 *
 * Holds the item id, selected format, owner ids and a non-secret [MediaItem] snapshot — never a token
 * or URL. The actual stream is always re-resolved through Jellyfin at resume time, so nothing sensitive
 * is stored.
 */
@Serializable
data class PendingDownload(
    val itemId: String,
    val title: String,
    val format: PersistedFormat,
    /** Owner at enqueue time. Null only for queues written by older app builds. */
    val owner: DownloadOwner? = null,
    /** Complete non-secret gallery metadata, used to faithfully resume an interrupted request. */
    val mediaItem: MediaItem? = null,
)

/** A refactor-stable, serialisable projection of [DownloadFormat] (avoids polymorphic class-name coupling). */
@Serializable
data class PersistedFormat(
    val kind: String,
    val label: String = "",
    val container: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val maxBitrateBps: Long = 0,
) {
    fun toDownloadFormat(): DownloadFormat = when (kind) {
        KIND_TRANSCODE -> DownloadFormat.Transcode(label, container, videoCodec, audioCodec, maxBitrateBps)
        else -> DownloadFormat.Original
    }

    companion object {
        const val KIND_ORIGINAL = "original"
        const val KIND_TRANSCODE = "transcode"

        fun from(format: DownloadFormat): PersistedFormat = when (format) {
            is DownloadFormat.Transcode -> PersistedFormat(
                kind = KIND_TRANSCODE,
                label = format.label,
                container = format.container,
                videoCodec = format.videoCodec,
                audioCodec = format.audioCodec,
                maxBitrateBps = format.maxBitrateBps,
            )
            DownloadFormat.Original -> PersistedFormat(kind = KIND_ORIGINAL, label = format.label)
        }
    }
}

/** Pure resume-selection logic, kept framework-free so it is exhaustively unit-testable. */
object DownloadQueue {
    /**
     * Of the [pending] requests, the ones that should be (re)started: not already completed and not
     * already running this session. Stale entries for completed items are filtered out by the caller.
     */
    fun selectResumable(
        pending: List<PendingDownload>,
        completed: Set<DownloadIdentity>,
        active: Set<DownloadIdentity>,
    ): List<PendingDownload> =
        pending.filter { it.identity !in completed && it.identity !in active }

    /**
     * Whether a download failure is transient (worth auto-retrying and keeping in the queue for a later
     * resume) vs. permanent. A permanent failure (bad/HLS-only item, 401/403/404, malformed response)
     * must NOT be retried or it would re-fail on every launch; a transient one (network drop, timeout,
     * 5xx/429/408) should auto-recover.
     */
    fun isRecoverableFailure(e: Throwable): Boolean = when (e) {
        is IOException -> true // connection reset / timeout / DNS blip / read failure
        is JellyfinHttpException -> !e.isUnauthorized && UpstreamRetry.isRetryableStatus(e.code)
        else -> false // bad/HLS-only item, definitive 4xx, malformed response — retrying won't help
    }
}

/**
 * App-private, mutex-guarded JSON store of the not-yet-finished download requests. Lives alongside the
 * completed-downloads index in the sandboxed `downloads` dir (removed by "Delete all app data").
 */
class DownloadQueueStore private constructor(filesDir: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesDir, DIR)
    private val queueFile = File(dir, "queue.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(PendingDownload.serializer())
    private val mutex = Mutex()

    suspend fun all(): List<PendingDownload> = mutex.withLock {
        withContext(Dispatchers.IO) { loadUnlocked() }
    }

    /** Pending entries belonging to exactly [owner] (or the ownerless legacy scope when null). */
    suspend fun allForOwner(owner: DownloadOwner?): List<PendingDownload> = mutex.withLock {
        withContext(Dispatchers.IO) { loadUnlocked().filter { it.owner == owner } }
    }

    /** Insert or replace the request for an item within its owning Jellyfin account. */
    suspend fun add(entry: PendingDownload) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val updated = loadUnlocked().filterNot { it.identity == entry.identity } + entry
            writeUnlocked(updated)
        }
    }

    suspend fun remove(itemId: String, owner: DownloadOwner? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val identity = DownloadIdentity(owner, itemId)
            writeUnlocked(loadUnlocked().filterNot { it.identity == identity })
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { CrashSafeTextFile.delete(queueFile) }
    }

    private fun loadUnlocked(): List<PendingDownload> {
        return CrashSafeTextFile.readRecovering(queueFile) { json.decodeFromString(serializer, it) }
            ?: emptyList()
    }

    private fun writeUnlocked(entries: List<PendingDownload>) {
        dir.mkdirs()
        CrashSafeTextFile.write(queueFile, json.encodeToString(serializer, entries))
    }

    companion object {
        private const val DIR = "downloads"

        /** JVM test seam; production callers must use the application [Context] constructor. */
        internal fun forFilesDir(filesDir: File): DownloadQueueStore = DownloadQueueStore(filesDir)
    }
}
