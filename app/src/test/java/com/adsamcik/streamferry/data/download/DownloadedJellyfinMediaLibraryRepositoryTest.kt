package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadedJellyfinMediaLibraryRepositoryTest {

    @Test
    fun unavailableShelfRefreshStopsAfterOneProbeAndRetriesOnNextRefresh() = runBlocking {
        val root = Files.createTempDirectory("downloaded-refresh-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val owner = DownloadOwner("server", "user")
            val store = DownloadStore.forFilesDir(root)
            val items = (1..51).map { media("item-$it", "Movie ${it.toString().padStart(2, '0')}") }
            items.forEach { store.savePlayable(owner, it) }
            var unavailable = false
            var recovered = false
            var live = true
            var calls = 0
            val batchSizes = mutableListOf<Int>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = DownloadedJellyfinMediaLibraryRepository(EmptyRepository(), store, { owner },
                canRefreshDownloads = { live }, isRemoteUnavailable = { unavailable }, refreshScope = scope,
                refreshMetadata = { _, ids ->
                    calls++
                    batchSizes += ids.size
                    started.complete(Unit)
                    release.await()
                    unavailable = !recovered
                    Result.success(items.filter { it.id in ids })
                })
            val shelf = DownloadedJellyfinMediaLibraryRepository.OFFLINE_DOWNLOADS_ROOT_ID
            // A blocked metadata request cannot delay the verified local shelf.
            assertEquals(items, withTimeout(5_000) { repository.children(shelf).getOrThrow() })
            withTimeout(5_000) { started.await() }
            // Repeated opens coalesce while this refresh is pending.
            assertEquals(items, repository.children(shelf).getOrThrow())
            release.complete(Unit)
            scope.coroutineContext.job.children.toList().forEach { it.join() }
            assertEquals(1, calls)
            recovered = true
            assertEquals(items, repository.children(shelf).getOrThrow())
            scope.coroutineContext.job.children.toList().forEach { it.join() }
            assertEquals(3, calls, "A fresh request must probe again and continue after recovery")
            assertEquals(listOf(50, 50, 1), batchSizes)
            live = false
            assertEquals(items, repository.children(shelf).getOrThrow())
            assertEquals(3, calls, "A cache-only session must not attempt remote refresh")
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun accountChangeDuringRefreshStopsRemainingBatchesAndPreservesLocalFiles() = runBlocking {
        val root = Files.createTempDirectory("downloaded-refresh-owner").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val firstOwner = DownloadOwner("server", "first")
            var currentOwner = firstOwner
            val store = DownloadStore.forFilesDir(root)
            (1..51).forEach { store.savePlayable(firstOwner, media("item-$it", "Movie $it")) }
            var calls = 0
            val repository = DownloadedJellyfinMediaLibraryRepository(EmptyRepository(), store, { currentOwner },
                canRefreshDownloads = { true }, refreshScope = scope, refreshMetadata = { _, _ ->
                    calls++
                    currentOwner = DownloadOwner("server", "second")
                    Result.success(emptyList())
                })

            assertEquals(51, repository.children(DownloadedJellyfinMediaLibraryRepository.OFFLINE_DOWNLOADS_ROOT_ID).getOrThrow().size)
            scope.coroutineContext.job.children.toList().forEach { it.join() }
            assertEquals(1, calls)
            assertEquals(51, store.listForOwner(firstOwner).size)
            assertTrue(repository.children(DownloadedJellyfinMediaLibraryRepository.OFFLINE_DOWNLOADS_ROOT_ID).getOrThrow().isEmpty())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun offlineDownloadsRemainBrowsableAndSearchableWhenDelegateIsUnavailable() = runBlocking {
        val root = Files.createTempDirectory("downloaded-jellyfin-library").toFile()
        try {
            val owner = DownloadOwner("server-a", "user-a")
            val item = media("movie-1", "Offline Adventure", overview = "A cached adventure")
            val store = DownloadStore.forFilesDir(root)
            store.savePlayable(owner, item)
            val repository = DownloadedJellyfinMediaLibraryRepository(
                delegate = FailingRepository(),
                downloadStore = store,
                owner = { owner },
            )

            val roots = repository.videoLibraries().getOrThrow()
            assertEquals(listOf(DownloadedJellyfinMediaLibraryRepository.OFFLINE_DOWNLOADS_ROOT_ID), roots.map { it.id })
            assertEquals(listOf(item), repository.children(roots.single().id).getOrThrow())
            assertEquals(item, repository.item(item.id).getOrThrow())
            assertEquals(listOf(item), repository.search("adventure").getOrThrow())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scopedDownloadNeverAppearsUnderAnotherJellyfinAccount() = runBlocking {
        val root = Files.createTempDirectory("downloaded-jellyfin-owner").toFile()
        try {
            val ownerA = DownloadOwner("server-a", "user-a")
            val ownerB = DownloadOwner("server-b", "user-b")
            val store = DownloadStore.forFilesDir(root)
            store.savePlayable(ownerA, media("same-id", "A's Offline Movie"))
            store.savePlayable(ownerB, media("same-id", "B's Offline Movie"))
            val repository = DownloadedJellyfinMediaLibraryRepository(
                delegate = EmptyRepository(),
                downloadStore = store,
                owner = { ownerA },
            )

            val roots = repository.videoLibraries().getOrThrow()
            assertEquals(1, roots.size)
            assertEquals(listOf("A's Offline Movie"), repository.children(roots.single().id).getOrThrow().map { it.title })
            assertEquals(listOf("A's Offline Movie"), repository.search("movie").getOrThrow().map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun media(id: String, title: String, overview: String? = null) = MediaItem(
        id = id,
        title = title,
        year = 2026,
        runtimeSeconds = 120,
        overview = overview,
        resumePositionSeconds = null,
        isFolder = false,
        type = "Movie",
    )

    private suspend fun DownloadStore.savePlayable(owner: DownloadOwner, item: MediaItem) {
        val entry = DownloadEntry(
            itemId = item.id,
            title = item.title,
            fileName = DownloadIdentity(owner, item.id).fileName("mp4", "video/mp4"),
            mimeType = "video/mp4",
            sizeBytes = 3,
            owner = owner,
            mediaItem = item,
        )
        fileFor(entry).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        upsert(entry)
        assertTrue(get(item.id, owner) != null)
    }

    private class FailingRepository : MediaLibraryRepository {
        override suspend fun videoLibraries(): Result<List<MediaItem>> = Result.failure(IllegalStateException("offline"))
        override suspend fun children(parentId: String): Result<List<MediaItem>> = Result.failure(IllegalStateException("offline"))
        override suspend fun item(itemId: String): Result<MediaItem> = Result.failure(IllegalStateException("offline"))
        override suspend fun search(query: String): Result<List<MediaItem>> = Result.failure(IllegalStateException("offline"))
    }

    private class EmptyRepository : MediaLibraryRepository {
        override suspend fun videoLibraries(): Result<List<MediaItem>> = Result.success(emptyList())
        override suspend fun children(parentId: String): Result<List<MediaItem>> = Result.success(emptyList())
        override suspend fun item(itemId: String): Result<MediaItem> = Result.failure(IllegalStateException("not found"))
        override suspend fun search(query: String): Result<List<MediaItem>> = Result.success(emptyList())
    }
}
