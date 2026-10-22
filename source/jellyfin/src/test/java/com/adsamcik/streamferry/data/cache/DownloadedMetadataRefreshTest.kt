package com.adsamcik.streamferry.data.cache

import android.content.Context
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure JVM filesystem checks, using the existing cache writer seam rather than an older Android API. */
class DownloadedMetadataRefreshTest {
    @Test
    fun `batch refresh publishes once and cannot undo a newer local watch edit`() = runBlocking {
        val root = Files.createTempDirectory("download-metadata-batch").toFile()
        try {
            val changes = mutableListOf<List<MediaItem>>()
            val cache = LibraryCache(context(root), fileWriter = { file, text -> file.parentFile?.mkdirs(); file.writeText(text) },
                onItemsChanged = { _, items -> changes += items })
            val first = media("first", 20)
            val second = media("second", 0)
            cache.put("owner", "seed", listOf(first, second))
            changes.clear()
            val repository = CachingMediaLibraryRepository(mockk<MediaLibraryRepository>(), cache, { "owner" })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = async(Dispatchers.Default) {
                repository.refreshItems(listOf("first", "second")) {
                    started.complete(Unit)
                    release.await()
                    Result.success(listOf(first.copy(resumePositionSeconds = 5), second))
                }
            }
            started.await()
            cache.patchItem("owner", "first") { it.copy(resumePositionSeconds = 80, played = true) }
            release.complete(Unit)

            val updated = request.await().getOrThrow()
            assertEquals(80, updated.first().resumePositionSeconds)
            assertTrue(updated.first().played)
            assertEquals(listOf(updated), changes, "One remote batch must produce one downstream download-index sync")
            assertEquals(80, cache.item("owner", "first")?.resumePositionSeconds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `account change discards in flight batch and cache only mode never calls source`() = runBlocking {
        val root = Files.createTempDirectory("download-metadata-scope").toFile()
        try {
            var active = "first-owner"
            var live = true
            val changes = mutableListOf<List<MediaItem>>()
            val cache = LibraryCache(context(root), fileWriter = { file, text -> file.parentFile?.mkdirs(); file.writeText(text) },
                onItemsChanged = { _, items -> changes += items })
            val repository = CachingMediaLibraryRepository(mockk<MediaLibraryRepository>(), cache, { active }, { live })
            val result = repository.refreshItems(listOf("movie")) {
                active = "second-owner"
                Result.success(listOf(media("movie", 90)))
            }
            assertTrue(result.isFailure)
            assertTrue(changes.isEmpty())
            assertNull(cache.item("first-owner", "movie"))
            assertNull(cache.item("second-owner", "movie"))
            live = false
            var called = false
            assertFalse(repository.refreshItems(listOf("movie")) { called = true; Result.success(emptyList()) }.isSuccess)
            assertFalse(called)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun context(root: java.io.File) = mockk<Context>().apply {
        every { applicationContext } returns this
        every { filesDir } returns root
    }

    private fun media(id: String, position: Long) = MediaItem(id, id, null, 120, null, position, false)
}
