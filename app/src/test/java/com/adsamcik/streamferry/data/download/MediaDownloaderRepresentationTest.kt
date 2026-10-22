package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.source.api.DownloadProvider
import com.adsamcik.streamferry.source.api.DownloadStream
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.StreamDescriptor
import com.adsamcik.streamferry.source.api.StreamLease
import com.adsamcik.streamferry.source.api.StreamResourceRef
import com.adsamcik.streamferry.source.api.StreamResponse
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaDownloaderRepresentationTest {
    @Test
    fun `failed metadata invalidation leaves old partial untouched before restart`() = runBlocking {
        val root = Files.createTempDirectory("download-validator-invalidation").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DownloadStore.forFilesDir(root)
            val item = MediaItem("movie", "Movie", null, null, null, null, false)
            val part = store.partFileFor(item.id).apply { parentFile.mkdirs(); writeText("ABC") }
            // A nonempty directory reliably refuses file deletion on Windows and Linux, without
            // depending on platform-specific sharing flags or whether the test process is privileged.
            val metadata = store.partMetaFileFor(item.id).apply { mkdirs() }
            java.io.File(metadata, "retained-validator").writeText("\"old\"\n6")
            val reads = AtomicInteger()
            val body = object : ByteArrayInputStream("UVWXYZ".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads.incrementAndGet()
                    return super.read(buffer, offset, length)
                }
            }
            val lease = mockk<StreamLease>()
            every { lease.descriptor } returns StreamDescriptor("video/mp4", "mp4",
                isTranscoding = false, isByteSeekable = true, totalLength = 6)
            coEvery { lease.open(any<ByteRange>()) } returns Result.success(StreamResponse(200,
                mapOf("ETag" to "\"new\"", "Content-Length" to "6"), body))
            val provider = mockk<DownloadProvider>()
            val retryStarted = CompletableDeferred<Unit>()
            var attempts = 0
            coEvery { provider.prepareDownload(any(), any()) } coAnswers {
                if (++attempts == 1) Result.success(DownloadStream(firstArg(), lease, "mp4", null)) else {
                    retryStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            val downloader = MediaDownloader(provider, store, DownloadQueueStore.forFilesDir(root),
                mockk<DiagnosticsLogger>(relaxed = true), scope)
            downloader.download(item)
            withTimeout(20_000) { retryStarted.await() }
            downloader.pauseAll()
            scope.coroutineContext.job.children.toList().forEach { it.join() }

            assertEquals("ABC", part.readText())
            assertEquals("\"old\"\n6", java.io.File(metadata, "retained-validator").readText())
            assertEquals(0, reads.get(), "A restart must not consume or truncate new bytes until old resume metadata is invalidated")
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `append requires matching strong entity tag and exact complete remaining range`() = runBlocking {
        val cases = listOf(
            Case("\"v1\"", "\"v1\"", "bytes 3-5/6", "3", true),
            Case("\"v1\"", "\"v2\"", "bytes 3-5/6", "3", false),
            Case("\"v1\"", null, "bytes 3-5/6", "3", false),
            Case("\"v1\"", "W/\"v1\"", "bytes 3-5/6", "3", false),
            Case("\"v1\"", "\"v1\"", "bytes 3-5/*", "3", false),
            Case("\"v1\"", "\"v1\"", "bytes 3-4/6", "2", false),
            Case("\"v1\"", "\"v1\"", "bytes 3-5/6", "4", false),
            Case("\"v1\"", "\"v1\"", "bytes 3-5/6", "invalid", false),
            Case("W/\"v1\"", "\"v1\"", "bytes 3-5/6", "3", false, rangeRequested = false),
            Case("Wed, 21 Oct 2026 07:28:00 GMT", null, "bytes 3-5/6", "3", false, rangeRequested = false),
        )
        for (case in cases) {
            val root = Files.createTempDirectory("download-representation").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = DownloadStore.forFilesDir(root)
                val item = MediaItem("movie", "Movie", null, null, null, null, false)
                store.partFileFor(item.id).apply { parentFile.mkdirs(); writeText("ABC") }
                store.partMetaFileFor(item.id).writeText("${case.savedTag}\n6")
                val requests = mutableListOf<ByteRange?>()
                val lease = object : StreamLease {
                    override val descriptor = StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 6)
                    override suspend fun open(range: ByteRange?): Result<StreamResponse> {
                        requests += range
                        val headers = if (range == null) mapOf("ETag" to "\"v2\"", "Content-Length" to "6") else
                            buildMap {
                                put("Content-Range", case.contentRange)
                                put("Content-Length", case.contentLength)
                                case.replyTag?.let { put("ETag", it) }
                            }
                        return Result.success(StreamResponse(if (range == null) 200 else 206, headers,
                            ByteArrayInputStream((if (range == null) "UVWXYZ" else "DEF").toByteArray())))
                    }
                    override suspend fun resolve(playlistReference: String, fromPlaylist: StreamResourceRef?) = Result.success<StreamResourceRef?>(null)
                    override suspend fun open(resource: StreamResourceRef, range: ByteRange?) = Result.failure<StreamResponse>(IllegalStateException("unused"))
                }
                val provider = object : DownloadProvider {
                    override suspend fun prepareDownload(media: MediaRef, format: DownloadFormat) =
                        Result.success(DownloadStream(media, lease, "mp4", null))
                }
                val downloader = MediaDownloader(provider, store, DownloadQueueStore.forFilesDir(root), mockk<DiagnosticsLogger>(relaxed = true), scope)
                downloader.download(item)
                val state = withTimeout(20_000) {
                    downloader.states.first { it[DownloadIdentity(null, item.id)] is MediaDownloader.DownloadState.Completed ||
                        it[DownloadIdentity(null, item.id)] is MediaDownloader.DownloadState.Failed }
                }
                scope.coroutineContext.job.children.forEach { it.join() }
                assertTrue(state[DownloadIdentity(null, item.id)] is MediaDownloader.DownloadState.Completed, "$case: $state")
                val entry = store.get(item.id)!!
                assertEquals(if (case.append) "ABCDEF" else "UVWXYZ", store.fileFor(entry).readText(), case.toString())
                val expectedRequests = when {
                    !case.rangeRequested -> listOf(null)
                    case.append -> listOf(ByteRange(3, Long.MAX_VALUE))
                    else -> listOf(ByteRange(3, Long.MAX_VALUE), null)
                }
                assertEquals(expectedRequests, requests, case.toString())
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
    }

    private data class Case(
        val savedTag: String, val replyTag: String?, val contentRange: String,
        val contentLength: String, val append: Boolean, val rangeRequested: Boolean = true,
    )
}
