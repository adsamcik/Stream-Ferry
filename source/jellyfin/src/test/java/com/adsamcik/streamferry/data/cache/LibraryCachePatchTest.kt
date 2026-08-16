package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.withProgressReset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCachePatchTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() = clearCache()

    @After
    fun tearDown() = clearCache()

    @Test
    fun patchItemUpdatesEveryCachedListAndOfflineSearchIndexWithoutRefreshingCacheAge() = runTest {
        var now = 1_000L
        val cache = LibraryCache(
            context = context,
            clock = { now },
            fileWriter = ::writeDirectly,
        )
        val scope = "server-user"
        val episode = episode(position = 210L, percentage = 42.0)

        cache.put(scope, LibraryCache.KEY_VIEWS, listOf(episode))
        cache.put(scope, LibraryCache.childrenKey("season-1"), listOf(episode.copy(overview = "Episode detail")))
        now = 9_000L

        cache.patchItem(scope, episode.id) { it.withProgressReset() }

        mapOf(
            "views" to cache.get(scope, LibraryCache.KEY_VIEWS)?.single(),
            "children" to cache.get(scope, LibraryCache.childrenKey("season-1"))?.single(),
            "index" to cache.item(scope, episode.id),
            "search" to cache.search(scope, "episode").single(),
        ).forEach { (source, patched) ->
            assertNotNull(patched, source)
            assertFalse(patched.played, source)
            assertEquals(0.0, patched.playedPercentage, source)
            assertEquals(null, patched.resumePositionSeconds, source)
        }
        assertEquals(1_000L, cache.getRecord(scope, LibraryCache.KEY_VIEWS)?.cachedAtMillis)
    }

    @Test
    fun aLateFreshPutKeepsTheConfirmedItemPatchInItsReturnedAndStoredData() = runTest {
        val cache = LibraryCache(
            context = context,
            clock = { 5_000L },
            fileWriter = ::writeDirectly,
        )
        val scope = "late-response-server-user"
        val stale = episode(position = 144L, percentage = 12.0)

        cache.put(scope, LibraryCache.childrenKey("season-1"), listOf(stale))
        cache.patchItem(scope, stale.id) { it.withProgressReset() }

        // Simulates a page request started before the action and completing afterwards.
        val returned = cache.put(scope, LibraryCache.childrenKey("season-1"), listOf(stale)).single()
        val stored = cache.get(scope, LibraryCache.childrenKey("season-1"))!!.single()

        listOf(returned, stored).forEach { item ->
            assertFalse(item.played)
            assertEquals(0.0, item.playedPercentage)
            assertEquals(null, item.resumePositionSeconds)
        }
    }
    @Test
    fun patchItemUpdatesAnIndexOnlyOfflineCache() = runTest {
        val cache = LibraryCache(
            context = context,
            clock = { 5_000L },
            fileWriter = ::writeDirectly,
        )
        val scope = "index-only-server-user"
        val episode = episode(position = 144L, percentage = 12.0)
        writeIndexOnly(scope, episode)

        cache.patchItem(scope, episode.id) { it.withProgressReset() }

        val patched = assertNotNull(cache.item(scope, episode.id))
        assertFalse(patched.played)
        assertEquals(0.0, patched.playedPercentage)
        assertEquals(null, patched.resumePositionSeconds)
        assertEquals(listOf(patched), cache.search(scope, "episode"))
    }


    @Test
    fun confirmedPatchYieldsToANewerAuthoritativeResponseButBlocksAnOlderOne() = runTest {
        val cache = LibraryCache(
            context = context,
            clock = { 5_000L },
            fileWriter = ::writeDirectly,
        )
        val scope = "ordered-response-server-user"
        val key = LibraryCache.childrenKey("season-1")
        val stale = episode(position = 144L, percentage = 12.0)
        val authoritative = episode(position = 360L, percentage = 30.0)

        cache.put(scope, key, listOf(stale))
        val olderRequest = cache.beginRemoteWrite(scope)
        val patch = cache.patchItem(scope, stale.id) { it.withProgressReset() }
        assertTrue(cache.confirmItemPatch(patch))
        val newerRequest = cache.beginRemoteWrite(scope)

        val fresh = cache.put(newerRequest, key, listOf(authoritative)).single()
        assertEquals(360L, fresh.resumePositionSeconds)
        assertFalse(cache.isItemPatchActive(patch))

        // This request began before confirmation and finishes afterwards. It must retain the newer item
        // in both its returned value and the shared cache/search index.
        val late = cache.put(olderRequest, key, listOf(stale)).single()
        val stored = cache.get(scope, key)!!.single()
        assertEquals(360L, late.resumePositionSeconds)
        assertEquals(360L, stored.resumePositionSeconds)
        assertEquals(360L, cache.search(scope, "episode").single().resumePositionSeconds)
    }

    private fun clearCache() {
        File(context.filesDir, "libcache").deleteRecursively()
    }

    private fun writeDirectly(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun writeIndexOnly(scope: String, item: MediaItem) {
        val dir = File(File(context.filesDir, "libcache"), safe(scope)).apply { mkdirs() }
        val encodedItems = json.encodeToString(ListSerializer(MediaItem.serializer()), listOf(item))
        File(dir, "index.json").writeText("{\"version\":1,\"items\":$encodedItems}")
    }

    private fun episode(position: Long, percentage: Double) = MediaItem(
        id = "episode-1",
        title = "Episode one",
        year = 2026,
        runtimeSeconds = 1_200,
        overview = null,
        resumePositionSeconds = position,
        isFolder = false,
        type = "Episode",
        played = false,
        playedPercentage = percentage,
    )

    private fun safe(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}