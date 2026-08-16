package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCacheTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true }

    @After
    fun tearDown() {
        File(context.filesDir, "libcache").deleteRecursively()
    }

    @Test
    fun putWritesTimestampedRecordAndKeepsListReadCompatibility() = runTest {
        val cache = LibraryCache(context) { 123_456L }
        val entries = listOf(media("one", "One"))

        assertFalse(cache.hasCachedData("server-user"))
        cache.put("server-user", LibraryCache.KEY_VIEWS, entries)

        assertEquals(entries, cache.get("server-user", LibraryCache.KEY_VIEWS))
        assertEquals(entries, cache.getRecord("server-user", LibraryCache.KEY_VIEWS)?.items)
        assertEquals(123_456L, cache.getRecord("server-user", LibraryCache.KEY_VIEWS)?.cachedAtMillis)
        assertTrue(cache.hasCachedData("server-user"))
    }

    @Test
    fun recordStateRetainsStaleDataAndSignalsSoftRefresh() = runTest {
        var now = 1_000L
        val cache = LibraryCache(context) { now }
        val entries = listOf(media("one", "One"))

        cache.put("server-user", LibraryCache.KEY_VIEWS, entries)
        now = 1_499L
        val fresh = cache.getRecordState("server-user", LibraryCache.KEY_VIEWS, refreshAfterMillis = 500L)
        assertEquals(entries, fresh?.record?.items)
        assertEquals(499L, fresh?.ageMillis)
        assertFalse(fresh?.refreshRecommended ?: true)

        now = 1_500L
        val stale = cache.getRecordState("server-user", LibraryCache.KEY_VIEWS, refreshAfterMillis = 500L)
        assertEquals(entries, stale?.record?.items)
        assertEquals(500L, stale?.ageMillis)
        assertTrue(stale?.refreshRecommended == true)
    }

    @Test
    fun legacyBareListIsReadableAndRebuildsTheOfflineIndex() = runTest {
        val scope = "legacy-server-user"
        val key = LibraryCache.childrenKey("library")
        val legacy = media("legacy", "Legacy feature", overview = "A restored cached description")
        val timestamp = 45_678L
        writeLegacyList(scope, key, listOf(legacy), timestamp)
        val cache = LibraryCache(context) { 999_999L }

        val record = cache.getRecord(scope, key)

        assertEquals(listOf(legacy), cache.get(scope, key))
        assertEquals(listOf(legacy), record?.items)
        assertEquals(timestamp, record?.cachedAtMillis)
        assertEquals(legacy, cache.item(scope, legacy.id))
        assertEquals(listOf(legacy), cache.search(scope, "RESTORED"))
        assertTrue(cache.hasCachedData(scope))
    }

    @Test
    fun searchAndItemIndexCoverEveryPutAndStayScoped() = runTest {
        val cache = LibraryCache(context) { 1L }
        val alpha = media("alpha", "Alpha Film", type = "Movie")
        val beta = media("beta", "Beta", subtitle = "Moon station", overview = "A deep-space mystery")
        val gamma = media("gamma", "Gamma", type = "Episode")

        cache.put("scope-a", LibraryCache.KEY_VIEWS, listOf(alpha, beta))
        cache.put("scope-a", LibraryCache.childrenKey("parent"), listOf(gamma))
        assertEquals(listOf(gamma), cache.get("scope-a", LibraryCache.childrenKey("parent")))
        writeStaleIndex("scope-a", listOf(alpha))
        cache.put("scope-b", LibraryCache.KEY_VIEWS, listOf(media("other", "Alpha elsewhere")))

        assertEquals(beta, cache.item("scope-a", "beta"))
        assertNull(cache.item("scope-a", "missing"))
        assertEquals(gamma, cache.item("scope-a", "gamma"))
        assertEquals(listOf(alpha), cache.search("scope-a", "ALPHA"))
        assertEquals(listOf(beta), cache.search("scope-a", "moon"))
        assertEquals(listOf(beta), cache.search("scope-a", "MYSTERY"))
        assertEquals(listOf(gamma), cache.search("scope-a", "episode"))
        assertEquals(listOf(alpha), cache.search("scope-a", "film"))
        assertEquals(emptyList(), cache.search("scope-a", "  "))
        assertEquals(emptyList(), cache.search("scope-b", "beta"))
    }

    @Test
    fun validEmptyListStillMarksTheScopeAsCachedAndClearRemovesIt() = runTest {
        val cache = LibraryCache(context) { 7L }

        cache.put("empty-scope", LibraryCache.KEY_RESUME, emptyList())

        assertTrue(cache.hasCachedData("empty-scope"))
        assertEquals(emptyList(), cache.get("empty-scope", LibraryCache.KEY_RESUME))
        cache.clear()
        assertFalse(cache.hasCachedData("empty-scope"))
    }

    private fun media(
        id: String,
        title: String,
        subtitle: String? = null,
        overview: String? = null,
        type: String? = null,
    ) = MediaItem(
        id = id,
        title = title,
        year = null,
        runtimeSeconds = null,
        overview = overview,
        resumePositionSeconds = null,
        isFolder = false,
        type = type,
        subtitle = subtitle,
    )

    private fun writeLegacyList(scope: String, key: String, items: List<MediaItem>, timestamp: Long) {
        val root = File(context.filesDir, "libcache")
        val dir = File(root, safe(scope)).apply { mkdirs() }
        val file = File(dir, safe(key) + ".json")
        file.writeText(json.encodeToString(ListSerializer(MediaItem.serializer()), items))
        check(file.setLastModified(timestamp))
    }

    private fun writeStaleIndex(scope: String, items: List<MediaItem>) {
        val root = File(context.filesDir, "libcache")
        val dir = File(root, safe(scope)).apply { mkdirs() }
        val file = File(dir, "index.json")
        val encodedItems = json.encodeToString(ListSerializer(MediaItem.serializer()), items)
        file.writeText("{\"version\":1,\"items\":$encodedItems}")
    }

    private fun safe(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
