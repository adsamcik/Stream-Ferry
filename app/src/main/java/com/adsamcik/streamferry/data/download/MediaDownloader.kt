package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.core.net.TrustedMediaOriginPolicy
import com.adsamcik.streamferry.core.resilience.Backoff
import com.adsamcik.streamferry.core.resilience.RetryBudget
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.data.jellyfin.DeviceProfiles
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.JellyfinRepository
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Optional offline downloader (§5 download exception): resolves an item's **original** (direct-play)
 * stream from Jellyfin and saves the full file to app-private storage for later, offline playback. The
 * streaming proxy stays RAM-only; this is a separate, strictly user-initiated path.
 *
 * Progress + status are exposed as a [StateFlow]; the persistent set of completed downloads lives in
 * [DownloadStore]. The Jellyfin token is used only to fetch the bytes server-side and is never written
 * to disk metadata.
 */
class MediaDownloader(
    private val jellyfin: JellyfinRepository,
    private val store: DownloadStore,
    private val queue: DownloadQueueStore,
    private val httpClient: OkHttpClient,
    private val logger: DiagnosticsLogger,
    private val scope: CoroutineScope,
    /** Non-null in production: the one account whose token/origin is installed in [jellyfin]. */
    private val activeOwnerProvider: (() -> DownloadOwner?)? = null,
) {
    /** Redirects are inspected below before an authenticated download request is re-issued. */
    private val pinnedHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    sealed interface DownloadState {
        data object Queued : DownloadState
        data class Running(val downloadedBytes: Long, val totalBytes: Long?) : DownloadState {
            val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
        }
        data object Completed : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    /**
     * Live download state keyed by the full server/user/item identity, never by a bare Jellyfin id.
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
                    logger.event("download", "Paused download until its Jellyfin account is active again")
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
        job.invokeOnCompletion { jobs.remove(identity, job) }
        if (jobs.putIfAbsent(identity, job) != null) {
            job.cancel() // another download for this item already owns the slot
            return
        }
        titles[identity] = item.title
        setState(identity, DownloadState.Queued)
        job.start()
    }

    /**
     * Re-enqueue persisted-but-unfinished requests for the currently authenticated Jellyfin owner.
     * The owner is required: the underlying Jellyfin client carries one server/token and must never
     * resolve another account's queued item after an account switch.
     */
    suspend fun resumePending(owner: DownloadOwner): Boolean {
        if (!isOwnerActive(owner)) return false
        return resumePendingEntries(owner, queue.allForOwner(owner))
    }

    /** True when this authenticated Jellyfin owner has a persisted unfinished request. */
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
     * Stop every active request before the singleton Jellyfin client changes server/user credentials.
     * Unlike [cancelAllAndJoin], it preserves the queue and `.part`/validator files for a later resume.
     */
    suspend fun pauseAllAndJoin() {
        val active = jobs.entries.toList()
        active.forEach { (identity, _) -> pauseRequests.add(identity) }
        active.forEach { (_, job) -> job.cancel() }
        active.forEach { (_, job) -> runCatching { job.join() } }
        active.forEach { (identity, _) ->
            // A job that was cancelled before its coroutine started never reaches the handler above.
            // Clear its marker after join so a later download of the same identity is not misclassified.
            pauseRequests.remove(identity)
            clearState(identity)
            titles.remove(identity)
        }
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
                // newly configured Jellyfin client.
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
        val info = when (format) {
            is DownloadFormat.Original -> jellyfin.playbackInfo(
                itemId = item.id,
                capabilities = DOWNLOAD_CAPS,
                maxBitrateBps = null,
                forceTranscode = false,
                allowSubtitleBurnIn = false,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                startPositionSeconds = 0,
            ).getOrThrow()
            is DownloadFormat.Transcode -> jellyfin.playbackInfo(
                itemId = item.id,
                capabilities = DOWNLOAD_CAPS,
                maxBitrateBps = format.maxBitrateBps,
                forceTranscode = true,
                allowSubtitleBurnIn = false,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                startPositionSeconds = 0,
                deviceProfileOverride = DeviceProfiles.forDownload(
                    format.maxBitrateBps,
                    format.container,
                    format.videoCodec,
                    format.audioCodec,
                ),
            ).getOrThrow()
        }
        val upstream = jellyfin.resolveUpstream(info)
        ensureOwnerActive(owner)
        require(!upstream.isHls) { "This title can only be streamed, not downloaded." }
        val originPolicy = TrustedMediaOriginPolicy.fromBaseUrl(upstream.url)
            ?: throw IOException("Refusing a download with an invalid Jellyfin origin.")

        val fileName = identity.fileName(info.profile.container, upstream.contentType)
        val part = store.partFileFor(item.id, owner)
        val meta = store.partMetaFileFor(item.id, owner)
        part.parentFile?.mkdirs()

        // Resume an interrupted download from the bytes already on disk. We send Range + If-Range, but
        // because some servers (incl. Jellyfin's static handler) ignore If-Range, we ALSO verify the
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

        fun fullRequest(): Request = Request.Builder().url(upstream.url).get()
            .apply { upstream.authHeader?.let { header("Authorization", it) } }
            .build()

        fun openPinned(request: Request): okhttp3.Response {
            var current = request
            repeat(MAX_DOWNLOAD_REDIRECTS + 1) {
                ensureOwnerActive(owner)
                if (!originPolicy.isTrusted(current.url)) {
                    throw IOException("Refusing a download request outside the configured Jellyfin origin.")
                }
                val response = pinnedHttpClient.newCall(current).execute()
                if (!response.isRedirect) return response
                val redirect = response.header("Location")?.let { originPolicy.resolve(it, current.url) }
                response.close()
                current = redirect?.let { current.newBuilder().url(it).build() }
                    ?: throw IOException("Refusing a download redirect outside the configured Jellyfin origin.")
            }
            throw IOException("Too many redirects from the configured Jellyfin origin.")
        }

        var resp = if (tryResume) {
            openPinned(
                Request.Builder().url(upstream.url).get().apply {
                    upstream.authHeader?.let { header("Authorization", it) }
                    storedValidator?.let { header("If-Range", it) }
                    header("Range", "bytes=$existing-")
                }.build(),
            )
        } else {
            openPinned(fullRequest())
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
                resp.code == 206 && startsAtExisting && sizeOk -> {
                    resumed = true
                    resumedRange = range
                }
                resp.code == 200 -> resumed = false // server returned the full entity; use it as a restart
                else -> {
                    // Range ignored, malformed, or for another offset: discard the stale validator and
                    // fetch a complete entity rather than splicing incompatible byte ranges together.
                    resp.close()
                    runCatching { meta.delete() }
                    resp = openPinned(fullRequest())
                    resumed = false
                }
            }
        }

        var downloaded = 0L
        var expectedTotal: Long? = null
        resp.use { r ->
            if (!r.isSuccessful) throw JellyfinHttpException(r.code)
            if (!resumed && r.code != 200) {
                throw IOException("Server returned a partial response to a full download request.")
            }
            val newValidator = r.header("ETag") ?: r.header("Last-Modified")
            val startAt = if (resumed) existing else 0L
            val remaining = r.body?.contentLength()?.takeIf { it >= 0 }
            val total = when {
                resumed && resumedRange?.total != null -> resumedRange?.total
                resumed && remaining != null -> startAt + remaining
                remaining != null -> remaining
                else -> upstream.totalLength
            }
            expectedTotal = total?.takeIf { it >= startAt }
            downloaded = startAt
            setState(identity, DownloadState.Running(downloaded, expectedTotal))
            val body = r.body ?: error("Empty response")
            body.byteStream().use { ins ->
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
                        coroutineContext.ensureActive() // cooperative cancellation
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

        // EOF is not proof of completion for a chunked body. If either the response or Jellyfin's
        // PlaybackInfo supplied a total, retain the partial file and let normal recovery resume it.
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
                mimeType = upstream.contentType,
                container = info.profile.container,
                sizeBytes = downloaded,
                runtimeSeconds = info.runtimeSeconds,
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

    /** A deliberate session boundary: retain queue/partial bytes until this owner is live again. */
    private class DownloadOwnerInactiveException : IOException(
        "Download paused until its Jellyfin account is active again.",
    )

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
        is DownloadOwnerInactiveException -> "Paused until this Jellyfin account is active again."
        is JellyfinHttpException -> e.serverReason?.let { "Server error: $it" } ?: "Server error (HTTP ${e.code})."
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "Download failed."
        else -> "Download failed. Check your connection and try again."
    }

    companion object {
        private const val TAG = "MediaDownloader"
        private const val PROGRESS_EMIT_BYTES = 1024L * 1024 // emit progress ~every 1 MiB
        private const val MAX_DOWNLOAD_REDIRECTS = 3
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        /** Broad direct-play capabilities so Jellyfin returns the ORIGINAL file (no transcode) to save. */
        private val DOWNLOAD_CAPS = TargetCapabilities(
            protocol = Protocol.CAST,
            supportedContainers = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v"),
            supportedVideoCodecs = setOf("h264", "hevc", "h265", "vp9", "vp8", "av1", "mpeg4", "mpeg2video"),
            supportedAudioCodecs = setOf("aac", "ac3", "eac3", "mp3", "opus", "flac", "vorbis", "dts", "truehd", "pcm"),
            supportsHevc = true,
            supports10Bit = true,
            supportsHls = false,
        )
    }
}
