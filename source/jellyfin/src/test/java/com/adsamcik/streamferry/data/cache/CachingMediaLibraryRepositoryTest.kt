package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.JellyfinLibraryStatus
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachingMediaLibraryRepositoryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        File(context.filesDir, "libcache").deleteRecursively()
    }

    @Test
    fun cacheOnlyScopeServesViewsDetailsAndSearchWithoutCallingRemote() = runTest {
        val cache = LibraryCache(context)
        val item = media("cached", "Cached Film", overview = "Seen before")
        cache.put(SCOPE, LibraryCache.KEY_VIEWS, listOf(item))
        val remote = RecordingRepository()
        val monitor = JellyfinConnectionMonitor()
        val repository = CachingMediaLibraryRepository(
            delegate = remote,
            cache = cache,
            scope = { SCOPE },
            isLiveSession = { false },
            connectionMonitor = monitor,
        )

        assertEquals(listOf(item), repository.videoLibraries().getOrThrow())
        assertEquals(item, repository.item(item.id).getOrThrow())
        assertEquals(listOf(item), repository.search("SEEN").getOrThrow())
        assertEquals(0, remote.callCount)
        assertEquals(JellyfinLibraryStatus.UNKNOWN, monitor.status.value)
    }

    @Test
    fun connectivityFailureFallsBackToCacheAndMarksServerUnavailable() = runTest {
        val cache = LibraryCache(context)
        val cached = media("cached", "Cached Film")
        cache.put(SCOPE, LibraryCache.KEY_VIEWS, listOf(cached))
        val monitor = JellyfinConnectionMonitor()
        val repository = CachingMediaLibraryRepository(
            delegate = RecordingRepository(views = Result.failure(IOException("offline"))),
            cache = cache,
            scope = { SCOPE },
            isLiveSession = { true },
            connectionMonitor = monitor,
        )

        assertEquals(listOf(cached), repository.videoLibraries().getOrThrow())
        assertEquals(JellyfinLibraryStatus.UNAVAILABLE, monitor.status.value)
    }

    @Test
    fun unauthorizedFailureIsNotHiddenByCachedMetadata() = runTest {
        val cache = LibraryCache(context)
        cache.put(SCOPE, LibraryCache.KEY_VIEWS, listOf(media("cached", "Cached Film")))
        val monitor = JellyfinConnectionMonitor()
        val repository = CachingMediaLibraryRepository(
            delegate = RecordingRepository(views = Result.failure(JellyfinHttpException(401))),
            cache = cache,
            scope = { SCOPE },
            isLiveSession = { true },
            connectionMonitor = monitor,
        )

        val result = repository.videoLibraries()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is JellyfinHttpException)
        assertFalse(monitor.status.value == JellyfinLibraryStatus.UNAVAILABLE)
    }

    @Test
    fun liveSearchUnionsRemoteAndPreviouslyCachedMetadata() = runTest {
        val cache = LibraryCache(context)
        val cached = media("cached", "A Cached Story", overview = "adventure")
        cache.put(SCOPE, LibraryCache.childrenKey("library"), listOf(cached))
        val remote = media("remote", "Remote Adventure")
        val repository = CachingMediaLibraryRepository(
            delegate = RecordingRepository(search = Result.success(listOf(remote))),
            cache = cache,
            scope = { SCOPE },
            isLiveSession = { true },
        )

        assertEquals(listOf(cached, remote), repository.search("adventure").getOrThrow())
    }

    private fun media(id: String, title: String, overview: String? = null) = MediaItem(
        id = id,
        title = title,
        year = null,
        runtimeSeconds = null,
        overview = overview,
        resumePositionSeconds = null,
        isFolder = false,
        type = "Movie",
    )

    private class RecordingRepository(
        private val views: Result<List<MediaItem>> = Result.success(emptyList()),
        private val search: Result<List<MediaItem>> = Result.success(emptyList()),
    ) : MediaLibraryRepository {
        var callCount = 0
            private set

        override suspend fun videoLibraries(): Result<List<MediaItem>> {
            callCount += 1
            return views
        }

        override suspend fun children(parentId: String): Result<List<MediaItem>> {
            callCount += 1
            return Result.success(emptyList())
        }

        override suspend fun item(itemId: String): Result<MediaItem> {
            callCount += 1
            return Result.failure(IllegalStateException("No remote item configured."))
        }

        override suspend fun search(query: String): Result<List<MediaItem>> {
            callCount += 1
            return search
        }

        override suspend fun continueWatching(): Result<List<MediaItem>> {
            callCount += 1
            return Result.success(emptyList())
        }
    }

    private companion object {
        const val SCOPE = "server_a_user_b"
    }
}