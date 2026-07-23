package com.videobridge.data.download

import com.videobridge.core.download.DownloadPaths
import com.videobridge.core.resilience.Backoff
import com.videobridge.core.resilience.RetryBudget
import com.videobridge.core.stream.Protocol
import com.videobridge.core.stream.TargetCapabilities
import com.videobridge.data.jellyfin.DeviceProfiles
import com.videobridge.data.jellyfin.JellyfinHttpException
import com.videobridge.domain.JellyfinRepository
import com.videobridge.domain.MediaItem
import com.videobridge.logging.DiagnosticsLogger
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
) {
    sealed interface DownloadState {
        data object Queued : DownloadState
        data class Running(val downloadedBytes: Long, val totalBytes: Long?) : DownloadState {
            val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
        }
        data object Completed : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Titles of in-flight downloads, so the UI can label a download resumed after process death. */
    private val titles = ConcurrentHashMap<String, String>()

    /** Title for an in-flight (queued/running) download, or null once it has completed/cleared. */
    fun titleFor(itemId: String): String? = titles[itemId]

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
    fun download(item: MediaItem, format: DownloadFormat = DownloadFormat.Original) {
        // Build the job LAZILY and claim the per-item slot atomically with putIfAbsent, so two callers
        // (e.g. the sticky-service restart and the connectivity callback both invoking resumePending on
        // different dispatchers) can never start two coroutines writing the same `.part` concurrently.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Persist the intent FIRST so a kill mid-download still leaves a resumable record.
                runCatching { queue.add(PendingDownload(item.id, item.title, PersistedFormat.from(format))) }
                    .onFailure { logger.w("download", "Failed to persist download intent; resume-after-kill unavailable", it) }
                logger.event("download", "Download started: ${item.title} (${format.label})")
                runDownloadWithRecovery(item, format)
                setState(item.id, DownloadState.Completed)
                logger.event("download", "Download completed: ${item.title}")
                titles.remove(item.id)
                runCatching { queue.remove(item.id) }
            } catch (c: CancellationException) {
                // Explicit cancel: discard the partial file + validator (a fresh start next time). The
                // queue entry is removed by cancel()/delete()/cancelAllAndJoin(), not here (we may be
                // running in an already-cancelled context where suspend calls can't complete).
                runCatching { store.partFileFor(item.id).delete() }
                runCatching { store.partMetaFileFor(item.id).delete() }
                clearState(item.id)
                throw c
            } catch (e: Exception) {
                if (isRecoverable(e)) {
                    // Transient (network/5xx): keep the `.part` AND the queue entry so it auto-resumes on
                    // the next connectivity callback, app launch, or sticky-service restart.
                    logger.w("download", "Download failed; will auto-resume (${e.javaClass.simpleName})", e)
                    setState(item.id, DownloadState.Failed(friendly(e)))
                } else {
                    // Permanent (404/403/auth, or a non-downloadable/HLS-only title): drop the queue
                    // entry so it is NOT relaunched forever, and discard the unusable partial file.
                    logger.e("download", "Download failed permanently (${e.javaClass.simpleName})", e)
                    runCatching { queue.remove(item.id) }
                    runCatching { store.partFileFor(item.id).delete() }
                    runCatching { store.partMetaFileFor(item.id).delete() }
                    setState(item.id, DownloadState.Failed(friendly(e)))
                }
            }
        }
        // Always untrack our own job on any terminal state (complete/fail/cancel, even if never started),
        // so a slot can't leak and block a future re-download.
        job.invokeOnCompletion { jobs.remove(item.id, job) }
        if (jobs.putIfAbsent(item.id, job) != null) {
            job.cancel() // another download for this item already owns the slot
            return
        }
        titles[item.id] = item.title
        setState(item.id, DownloadState.Queued)
        job.start()
    }

    /**
     * Re-enqueue any persisted-but-unfinished downloads (after a process kill or sticky-service restart).
     * Returns true if at least one download was (re)started. Idempotent: skips items already completed or
     * already running this session.
     */
    suspend fun resumePending(): Boolean {
        val pending = queue.all()
        if (pending.isEmpty()) return false
        val completed = runCatching { store.list().map { it.itemId }.toSet() }.getOrDefault(emptySet())
        // Drop stale entries whose download actually completed already.
        pending.filter { it.itemId in completed }.forEach { runCatching { queue.remove(it.itemId) } }
        val toResume = DownloadQueue.selectResumable(pending, completed, jobs.keys)
        toResume.forEach { p -> download(reconstructItem(p), p.format.toDownloadFormat()) }
        if (toResume.isNotEmpty()) logger.event("download", "Resuming ${toResume.size} download(s)")
        return toResume.isNotEmpty()
    }

    /** True if there is at least one persisted download that has not yet completed (for app-open resume). */
    suspend fun hasPendingPersisted(): Boolean {
        val pending = queue.all()
        if (pending.isEmpty()) return false
        val completed = runCatching { store.list().map { it.itemId }.toSet() }.getOrDefault(emptySet())
        return pending.any { it.itemId !in completed }
    }

    private fun reconstructItem(p: PendingDownload): MediaItem = MediaItem(
        id = p.itemId,
        title = p.title,
        year = null,
        runtimeSeconds = null,
        overview = null,
        resumePositionSeconds = null,
        isFolder = false,
    )

    fun cancel(itemId: String) {
        // Let the job's own cancellation handler delete the `.part` and remove itself from `jobs`,
        // so a subsequent re-download can't race that cleanup. (No eager removal / async delete here.)
        jobs[itemId]?.cancel()
        clearState(itemId)
        titles.remove(itemId)
        scope.launch { runCatching { queue.remove(itemId) } }
    }

    /** Cancel every in-flight download and wait for them to finish unwinding (used by delete-all). */
    suspend fun cancelAllAndJoin() {
        val active = jobs.values.toList()
        active.forEach { it.cancel() }
        active.forEach { runCatching { it.join() } }
        titles.clear()
        runCatching { queue.clear() }
        _states.value = emptyMap()
    }

    suspend fun delete(itemId: String) {
        // Cancel and fully unwind the job (its handler deletes the `.part`) before removing files, so
        // a still-running write can't recreate a `.part` after we delete it.
        jobs[itemId]?.let { it.cancel(); runCatching { it.join() } }
        clearState(itemId)
        titles.remove(itemId)
        runCatching { queue.remove(itemId) }
        store.remove(itemId)
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
    private suspend fun runDownloadWithRecovery(item: MediaItem, format: DownloadFormat) {
        var consecutiveFailures = 0
        var highWaterBytes = currentPartLength(item.id)
        while (true) {
            try {
                runDownload(item, format)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!isRecoverable(e)) throw e
                val len = currentPartLength(item.id)
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

    private fun currentPartLength(itemId: String): Long =
        runCatching { store.partFileFor(itemId).length() }.getOrDefault(0L)

    /** Whether a failure is transient (worth auto-retrying / keeping for later resume) vs. permanent. */
    private fun isRecoverable(e: Throwable): Boolean = DownloadQueue.isRecoverableFailure(e)

    private suspend fun runDownload(item: MediaItem, format: DownloadFormat) {
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
        require(!upstream.isHls) { "This title can only be streamed, not downloaded." }

        val fileName = DownloadPaths.fileName(item.id, info.profile.container, upstream.contentType)
        val part = store.partFileFor(item.id)
        val meta = store.partMetaFileFor(item.id)
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

        var resp = if (tryResume) {
            httpClient.newCall(
                Request.Builder().url(upstream.url).get().apply {
                    upstream.authHeader?.let { header("Authorization", it) }
                    storedValidator?.let { header("If-Range", it) }
                    header("Range", "bytes=$existing-")
                }.build(),
            ).execute()
        } else {
            httpClient.newCall(fullRequest()).execute()
        }

        var resumed = false
        if (tryResume) {
            val crTotal = parseContentRangeTotal(resp.header("Content-Range"))
            val sizeOk = storedTotal <= 0 || crTotal <= 0 || crTotal == storedTotal
            when {
                resp.code == 206 && sizeOk -> resumed = true
                resp.code == 200 -> resumed = false // server returned the full entity; use it as a restart
                else -> {
                    // Range ignored with a non-200, or the file changed size: discard the stale partial
                    // and fetch the whole file fresh.
                    resp.close()
                    runCatching { meta.delete() }
                    resp = httpClient.newCall(fullRequest()).execute()
                    resumed = false
                }
            }
        }

        var downloaded = 0L
        resp.use { r ->
            if (!r.isSuccessful) throw JellyfinHttpException(r.code)
            val newValidator = r.header("ETag") ?: r.header("Last-Modified")
            val startAt = if (resumed) existing else 0L
            val remaining = r.body?.contentLength()?.takeIf { it >= 0 }
            val total = when {
                resumed && remaining != null -> startAt + remaining
                remaining != null -> remaining
                else -> upstream.totalLength
            }
            downloaded = startAt
            setState(item.id, DownloadState.Running(downloaded, total))
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
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastEmit >= PROGRESS_EMIT_BYTES) {
                            lastEmit = downloaded
                            setState(item.id, DownloadState.Running(downloaded, total))
                        }
                    }
                    out.flush()
                }
            }
        }

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
            ),
        )
        logger.event("download", "Saved offline copy (${downloaded / (1024 * 1024)} MiB)")
    }

    private fun setState(id: String, s: DownloadState) = _states.update { it + (id to s) }
    private fun clearState(id: String) = _states.update { it - id }

    /** Parse the total length from a `Content-Range: bytes start-end/total` header (-1 if unknown). */
    private fun parseContentRangeTotal(header: String?): Long {
        val total = header?.substringAfterLast('/', "")?.trim().orEmpty()
        return total.toLongOrNull() ?: -1L
    }

    private fun friendly(e: Exception): String = when (e) {
        is JellyfinHttpException -> e.serverReason?.let { "Server error: $it" } ?: "Server error (HTTP ${e.code})."
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "Download failed."
        else -> "Download failed. Check your connection and try again."
    }

    companion object {
        private const val TAG = "MediaDownloader"
        private const val PROGRESS_EMIT_BYTES = 1024L * 1024 // emit progress ~every 1 MiB

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
