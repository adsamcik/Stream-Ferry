package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.source.api.DownloadProvider
import com.adsamcik.streamferry.source.api.StreamResponse
import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.resilience.Backoff
import com.adsamcik.streamferry.core.resilience.RetryBudget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Optional offline downloader (§5 download exception): resolves an item's **original** (direct-play)
 * stream from its source and saves the full file to app-private storage for later, offline playback. The
 * streaming proxy stays RAM-only; this is a separate, strictly user-initiated path.
 *
 * Progress + status are exposed as a [StateFlow]; the persistent set of completed downloads lives in
 * [DownloadStore]. Source credentials remain behind [DownloadProvider] and are never written to disk.
 */
class MediaDownloader(
    private val downloadProvider: DownloadProvider,
    private val store: DownloadStore,
    private val queue: DownloadQueueStore,
    private val logger: DiagnosticsLogger,
    private val scope: CoroutineScope,
    /** Non-null in production: the one account whose token/origin is installed in [jellyfin]. */
    private val activeOwnerProvider: (() -> DownloadOwner?)? = null,
) {

    sealed interface DownloadState {
        data object Queued : DownloadState
        data class Running(val downloadedBytes: Long, val totalBytes: Long?) : DownloadState {
            val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
        }
        data object Completed : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    /**
     * Live download state keyed by the full source-owner/item identity, never by a bare native id.
     * Consumers must filter by [DownloadIdentity.owner] before presenting a server's downloads.
     */
    private val _states = MutableStateFlow<Map<DownloadIdentity, DownloadState>>(emptyMap())
    val states: StateFlow<Map<DownloadIdentity, DownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<DownloadIdentity, Job>()
    /** Identities explicitly paused for an auth transition; their queue rows and partial bytes survive. */
    private val pauseRequests = ConcurrentHashMap.newKeySet<DownloadIdentity>()

    /** Titles of in-flight downloads, so the UI can label a download resumed after process death. */
    private val titles = ConcurrentHashMap<DownloadIdentity, String>()

    /** Title for an in-flight (queued/running) download, or null once it has completed/cleared. */
    fun titleFor(itemId: String, owner: DownloadOwner? = null): String? =
        titles[DownloadIdentity(owner, itemId)]

    // Progress-aware retry budget so a download auto-recovers across brief outages, server flakiness,
    // or Wi-Fi<->cellular switches without user action. The consecutive-failure counter resets whenever
    // an attempt makes forward progress, so a long download over a flaky link keeps going rather than
    // giving up after a fixed number of dips.
    private val retryBudget = RetryBudget(
        maxConsecutiveFailures = 6,
        baseDelayMillis = 1_000,
        maxDelayMillis = 30_000,
    )

    /** Start downloading [item] with the chosen [format] (no-op if already in progress). A previously
     *  interrupted Original download resumes from its `.part` file; Transcode downloads always restart.
     *  The request is persisted so it can be resumed automatically if the process is killed. */
    fun download(
        item: MediaItem,
        format: DownloadFormat = DownloadFormat.Original,
        owner: DownloadOwner? = null,
    ) {
        val identity = DownloadIdentity(owner, item.id)
        // Build the job LAZILY and claim the per-item slot atomically with putIfAbsent, so two callers
        // (e.g. the sticky-service restart and the connectivity callback both invoking resumePending on
        // different dispatchers) can never start two coroutines writing the same `.part` concurrently.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Persist the intent FIRST so a kill mid-download still leaves a resumable record.
                runCatching {
                    queue.add(
                        PendingDownload(
                            itemId = item.id,
                            title = item.title,
                            format = PersistedFormat.from(format),
                            owner = owner,
                            mediaItem = item,
                        ),
                    )
                }
                    .onFailure { logger.w("download", "Failed to persist download intent; resume-after-kill unavailable", it) }
                logger.event("download", "Download started: ${item.title} (${format.label})")
                runDownloadWithRecovery(item, format, owner)
                setState(identity, DownloadState.Completed)
                logger.event("download", "Download completed: ${item.title}")
                titles.remove(identity)
                runCatching { queue.remove(item.id, owner) }
            } catch (c: CancellationException) {
                // An auth transition pauses rather than cancels: retain the queue row, validator, and
                // partial bytes so the same owner can safely resume later. Explicit cancellation/delete
                // still discards the partial copy through the normal path below.
                if (pauseRequests.remove(identity)) {
                    logger.event("download", "Paused download until its source account is active again")
                } else {
                    runCatching { store.partFileFor(item.id, owner).delete() }
                    runCatching { store.partMetaFileFor(item.id, owner).delete() }
                }
                clearState(identity)
                titles.remove(identity)
                throw c
            } catch (e: Exception) {
                if (isRecoverable(e)) {
                    // Transient (network/5xx): keep the `.part` AND the queue entry so it auto-resumes on
                    // the next connectivity callback, app launch, or sticky-service restart.
                    logger.w("download", "Download failed; will auto-resume (${e.javaClass.simpleName})", e)
                    setState(identity, DownloadState.Failed(friendly(e)))
                } else {
                    // Permanent (404/403/auth, or a non-downloadable/HLS-only title): drop the queue
                    // entry so it is NOT relaunched forever, and discard the unusable partial file.
                    logger.e("download", "Download failed permanently (${e.javaClass.simpleName})", e)
                    runCatching { queue.remove(item.id, owner) }
                    runCatching { store.partFileFor(item.id, owner).delete() }
                    runCatching { store.partMetaFileFor(item.id, owner).delete() }
                    setState(identity, DownloadState.Failed(friendly(e)))
                }
            }
        }
        // Always untrack our own job on any terminal state (complete/fail/cancel, even if never started),
        // so a slot can't leak and block a future re-download.
        job.invokeOnCompletion {
            jobs.remove(identity, job)
            // A lazily-created job can be paused before its coroutine reaches the cancellation handler.
            // Clean that case here while preserving the persisted queue and partial file.
            if (pauseRequests.remove(identity)) {
                clearState(identity)
                titles.remove(identity)
            }
        }
        if (jobs.putIfAbsent(identity, job) != null) {
            job.cancel() // another download for this item already owns the slot
            return
        }
        titles[identity] = item.title
        setState(identity, DownloadState.Queued)
        job.start()
    }

    /**
     * Re-enqueue persisted-but-unfinished requests for the currently authenticated source owner.
     * The owner is required: the active source runtime carries one account and must never
     * resolve another account's queued item after an account switch.
     */
    suspend fun resumePending(owner: DownloadOwner): Boolean {
        if (!isOwnerActive(owner)) return false
        return resumePendingEntries(owner, queue.allForOwner(owner))
    }

    /** True when this authenticated source owner has a persisted unfinished request. */
    suspend fun hasPendingPersisted(owner: DownloadOwner): Boolean =
        hasPendingEntries(owner, queue.allForOwner(owner))

    private suspend fun resumePendingEntries(
        owner: DownloadOwner,
        pending: List<PendingDownload>,
    ): Boolean {
        val uniquePending = pending.distinctBy { it.identity }
        if (uniquePending.isEmpty()) return false
        val completed = runCatching { store.list(owner).map { it.identity }.toSet() }.getOrDefault(emptySet())
        // Drop stale entries whose download actually completed already.
        uniquePending.filter { it.identity in completed }.forEach { pendingEntry ->
            runCatching { queue.remove(pendingEntry.itemId, owner) }
        }
        val toResume = DownloadQueue.selectResumable(uniquePending, completed, jobs.keys)
        toResume.forEach { pendingEntry ->
            download(reconstructItem(pendingEntry), pendingEntry.format.toDownloadFormat(), owner)
        }
        if (toResume.isNotEmpty()) logger.event("download", "Resuming ${toResume.size} download(s)")
        return toResume.isNotEmpty()
    }

    private suspend fun hasPendingEntries(
        owner: DownloadOwner,
        pending: List<PendingDownload>,
    ): Boolean {
        if (pending.isEmpty()) return false
        val completed = runCatching { store.list(owner).map { it.identity }.toSet() }.getOrDefault(emptySet())
        return pending.any { it.identity !in completed }
    }

    private fun reconstructItem(p: PendingDownload): MediaItem =
        p.mediaItem?.takeIf { it.id == p.itemId } ?: MediaItem(
            id = p.itemId,
            title = p.title,
            year = null,
            runtimeSeconds = null,
            overview = null,
            resumePositionSeconds = null,
            isFolder = false,
        )

    fun cancel(itemId: String, owner: DownloadOwner? = null) {
        val identity = DownloadIdentity(owner, itemId)
        pauseRequests.remove(identity)
        // Let the job's own cancellation handler delete the `.part` and remove itself from `jobs`,
        // so a subsequent re-download can't race that cleanup. (No eager removal / async delete here.)
        jobs[identity]?.cancel()
        clearState(identity)
        titles.remove(identity)
        scope.launch { runCatching { queue.remove(itemId, owner) } }
    }

    /** Cancel every in-flight download and wait for them to finish unwinding (used by delete-all). */
    suspend fun cancelAllAndJoin() {
        pauseRequests.clear()
        val active = jobs.values.toList()
        active.forEach { it.cancel() }
        active.forEach { runCatching { it.join() } }
        titles.clear()
        runCatching { queue.clear() }
        _states.value = emptyMap()
    }

    /**
     * Stop every active request before the source runtime changes account credentials.
     * Unlike [cancelAllAndJoin], it preserves the queue and `.part`/validator files for a later resume.
     */
    suspend fun pauseAllAndJoin() {
        val active = requestPauseAll()
        active.forEach { (_, job) -> runCatching { job.join() } }
        active.forEach { (identity, _) ->
            // A job that was cancelled before its coroutine started never reaches the handler above.
            // Clear its marker after join so a later download of the same identity is not misclassified.
            pauseRequests.remove(identity)
            clearState(identity)
            titles.remove(identity)
        }
    }

    /**
     * Mark every active download as resumable and request cancellation immediately, without waiting for
     * its coroutine to unwind. Foreground-service timeout callbacks use this non-suspending form because
     * Android requires the service to stop within a short deadline.
     */
    fun pauseAll() {
        requestPauseAll()
    }

    private fun requestPauseAll(): List<Map.Entry<DownloadIdentity, Job>> {
        val active = jobs.entries.toList()
        active.forEach { (identity, _) -> pauseRequests.add(identity) }
        active.forEach { (_, job) -> job.cancel() }
        return active
    }

    suspend fun delete(itemId: String, owner: DownloadOwner? = null) {
        val identity = DownloadIdentity(owner, itemId)
        pauseRequests.remove(identity)
        // Cancel and fully unwind the job (its handler deletes the `.part`) before removing files, so
        // a still-running write can't recreate a `.part` after we delete it.
        jobs[identity]?.let { it.cancel(); runCatching { it.join() } }
        clearState(identity)
        titles.remove(identity)
        runCatching { queue.remove(itemId, owner) }
        store.remove(itemId, owner)
    }

    /**
     * Run [runDownload], automatically retrying recoverable (transient) failures with progress-aware
     * exponential backoff. The consecutive-failure counter only resets on **genuine net new progress** —
     * an **Original** (Range-resumable) download whose `.part` grew beyond any previous high-water mark.
     * A **Transcode** download restarts every attempt, and a server that ignores `Range` (returns 200)
     * truncates and re-writes; neither is allowed to reset the budget, so a flaky link can never loop
     * forever — those failures strictly count toward [RetryBudget.maxConsecutiveFailures] and then
     * propagate (to be resumed later). Permanent failures (bad/HLS-only item, 4xx) are not retried.
     */
    private suspend fun runDownloadWithRecovery(
        item: MediaItem,
        format: DownloadFormat,
        owner: DownloadOwner?,
    ) {
        var consecutiveFailures = 0
        var highWaterBytes = currentPartLength(item.id, owner)
        while (true) {
            try {
                ensureOwnerActive(owner)
                runDownload(item, format, owner)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // An account transition is a deliberate pause boundary, never a retry loop against the
                // newly configured source runtime.
                if (e is DownloadOwnerInactiveException || !isRecoverable(e)) throw e
                val len = currentPartLength(item.id, owner)
                val madeProgress = format is DownloadFormat.Original && len > highWaterBytes
                highWaterBytes = maxOf(highWaterBytes, len)
                consecutiveFailures = if (madeProgress) 0 else consecutiveFailures + 1
                if (consecutiveFailures > retryBudget.maxConsecutiveFailures) throw e
                val backoffMs = Backoff.delayMillis(retryBudget, consecutiveFailures, ThreadLocalRandom.current().nextDouble())
                logger.event("download", "auto-recovering download (retry $consecutiveFailures) after ${backoffMs}ms")
                if (backoffMs > 0) delay(backoffMs)
            }
        }
    }

    private fun currentPartLength(itemId: String, owner: DownloadOwner?): Long =
        runCatching { store.partFileFor(itemId, owner).length() }.getOrDefault(0L)

    /** Whether a failure is transient (worth auto-retrying / keeping for later resume) vs. permanent. */
    private fun isRecoverable(e: Throwable): Boolean =
        e is DownloadOwnerInactiveException || DownloadQueue.isRecoverableFailure(e)

    /** Ownerless legacy jobs/tests are permitted; production owner jobs must match the installed session. */
    private fun isOwnerActive(owner: DownloadOwner?): Boolean {
        if (owner == null) return true
        val provider = activeOwnerProvider ?: return true
        return provider() == owner
    }

    private fun ensureOwnerActive(owner: DownloadOwner?) {
        if (!isOwnerActive(owner)) throw DownloadOwnerInactiveException()
    }

    private suspend fun runDownload(item: MediaItem, format: DownloadFormat, owner: DownloadOwner?) {
        val identity = DownloadIdentity(owner, item.id)
        ensureOwnerActive(owner)
        val prepared = downloadProvider.prepareDownload(item.ref, format).getOrThrow()
        val stream = prepared.stream
        val descriptor = stream.descriptor
        ensureOwnerActive(owner)
        require(!descriptor.isHls) { "This title can only be streamed, not downloaded." }

        val fileName = identity.fileName(prepared.container, descriptor.contentType)
        val part = store.partFileFor(item.id, owner)
        val meta = store.partMetaFileFor(item.id, owner)
        part.parentFile?.mkdirs()

        // Resume an interrupted download from the bytes already on disk. We send Range + If-Range, but
        // because some servers ignore conditional ranges, we ALSO verify the
        // resumed response's total size matches what we recorded — so a file that changed underneath us
        // forces a clean restart instead of splicing two different versions into one corrupt file.
        // Transcode downloads are re-encoded on each request and are NOT byte-range resumable.
        val existing = if (format is DownloadFormat.Original && part.exists()) part.length() else 0L
        val storedValidator: String?
        val storedTotal: Long
        if (existing > 0 && meta.isFile) {
            val lines = runCatching { meta.readLines() }.getOrDefault(emptyList())
            storedValidator = lines.getOrNull(0)?.trim()?.ifBlank { null }
            storedTotal = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: -1L
        } else {
            storedValidator = null
            storedTotal = -1L
        }
        val tryResume = existing > 0 && (storedValidator != null || storedTotal > 0)

        var resp = if (tryResume) {
            stream.open(ByteRange(existing, Long.MAX_VALUE)).getOrThrow()
        } else {
            stream.open().getOrThrow()
        }

        var resumed = false
        var resumedRange: ContentRange? = null
        if (tryResume) {
            val range = parseContentRange(resp.header("Content-Range"))
            val startsAtExisting = range?.start == existing
            val sizeOk = storedTotal <= 0 || range?.total == null || range.total == storedTotal
            when {
                // A matching total alone is insufficient: appending a 206 that starts elsewhere silently
                // corrupts the offline copy. Require the exact byte the partial file ends at.
                resp.statusCode == 206 && startsAtExisting && sizeOk -> {
                    resumed = true
                    resumedRange = range
                }
                resp.statusCode == 200 -> resumed = false // source returned the full entity; use it as a restart
                else -> {
                    // Range ignored, malformed, or for another offset: discard the stale validator and
                    // fetch a complete entity rather than splicing incompatible byte ranges together.
                    resp.close()
                    runCatching { meta.delete() }
                    resp = stream.open().getOrThrow()
                    resumed = false
                }
            }
        }

        var downloaded = 0L
        var expectedTotal: Long? = null
        resp.use { r ->
            if (r.statusCode !in 200..299) throw DownloadHttpException(r.statusCode)
            if (!resumed && r.statusCode != 200) {
                throw IOException("Source returned a partial response to a full download request.")
            }
            val newValidator = r.header("ETag") ?: r.header("Last-Modified")
            val startAt = if (resumed) existing else 0L
            val remaining = r.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            val total = when {
                resumed && resumedRange?.total != null -> resumedRange.total
                resumed && remaining != null -> startAt + remaining
                remaining != null -> remaining
                else -> descriptor.totalLength
            }
            expectedTotal = total?.takeIf { it >= startAt }
            downloaded = startAt
            setState(identity, DownloadState.Running(downloaded, expectedTotal))
            val downloadContext = coroutineContext
            // One interruptible region covers the full socket/file copy, avoiding per-chunk dispatcher
            // overhead while still interrupting a blocked read as soon as pauseAll() cancels the job.
            runInterruptible(Dispatchers.IO) {
                r.body.use { ins ->
                    val fos = FileOutputStream(part, /* append = */ resumed)
                    // Opening in non-append mode truncated the file: record the validator + total for *these*
                    // bytes (after the truncate) so a future resume can verify the same file version.
                    if (!resumed) {
                        runCatching { meta.writeText((newValidator ?: "") + "\n" + (total ?: -1L)) }
                    }
                    fos.buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastEmit = downloaded
                        while (true) {
                            downloadContext.ensureActive()
                            ensureOwnerActive(owner)
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (downloaded - lastEmit >= PROGRESS_EMIT_BYTES) {
                                lastEmit = downloaded
                                setState(identity, DownloadState.Running(downloaded, total))
                            }
                        }
                        out.flush()
                    }
                }
            }
        }

        // EOF is not proof of completion for a chunked body. If either the response or source descriptor
        // supplied a total, retain the partial file and let normal recovery resume it.
        expectedTotal?.let { expected ->
            if (downloaded != expected) {
                throw IOException("Download ended at $downloaded bytes; expected $expected bytes.")
            }
        }

        // Do not publish a completed entry under an owner after the singleton client moved elsewhere.
        // Leave the validated partial intact so that owner can resume safely when they return.
        ensureOwnerActive(owner)
        runCatching { meta.delete() } // validator no longer needed once fully downloaded

        val finalFile = File(part.parentFile, fileName)
        runCatching { finalFile.delete() }
        if (!part.renameTo(finalFile)) {
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
        store.upsert(
            DownloadEntry(
                itemId = item.id,
                title = item.title,
                fileName = fileName,
                mimeType = descriptor.contentType,
                container = prepared.container,
                sizeBytes = downloaded,
                runtimeSeconds = prepared.runtimeSeconds,
                downloadedAtMillis = System.currentTimeMillis(),
                qualityLabel = format.label,
                owner = owner,
                mediaItem = item,
            ),
        )
        logger.event("download", "Saved offline copy (${downloaded / (1024 * 1024)} MiB)")
    }

    private fun setState(identity: DownloadIdentity, s: DownloadState) = _states.update { it + (identity to s) }
    private fun clearState(identity: DownloadIdentity) = _states.update { it - identity }

    private fun StreamResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** A deliberate session boundary: retain queue/partial bytes until this owner is live again. */
    private class DownloadOwnerInactiveException : IOException(
        "Download paused until its source account is active again.",
    )

    internal class DownloadHttpException(val statusCode: Int) : Exception("HTTP $statusCode")

    private data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?)

    /** Parse and validate a `Content-Range: bytes start-end/total` response header. */
    private fun parseContentRange(header: String?): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(header?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (start < 0L || end < start || (total != null && (total <= end || total <= 0L))) return null
        return ContentRange(start, end, total)
    }

    private fun friendly(e: Exception): String = when (e) {
        is DownloadOwnerInactiveException -> "Paused until this source account is active again."
        is DownloadHttpException -> "Server error (HTTP ${e.statusCode})."
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "Download failed."
        else -> "Download failed. Check your connection and try again."
    }

    companion object {
        private const val TAG = "MediaDownloader"
        private const val PROGRESS_EMIT_BYTES = 1024L * 1024 // emit progress ~every 1 MiB
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    }
}
