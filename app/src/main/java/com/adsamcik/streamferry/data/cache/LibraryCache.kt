package com.adsamcik.streamferry.data.cache

import android.content.Context
import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Optional on-disk cache of the Jellyfin library **metadata** (titles/ids/years/overviews — no media
 * bytes, no token) so browsing works offline and paints instantly (§8). Stored in app-private internal
 * storage (sandboxed; excluded from backup by `data_extraction_rules`) and removed by "Delete all app
 * data". Keyed by a per-server-and-user [scope] so different accounts never mix.
 */
class LibraryCache(context: Context) {

    private val root = File(context.applicationContext.filesDir, DIR)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MediaItem.serializer())

    suspend fun put(scope: String, key: String, items: List<MediaItem>) = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(scope, key)
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(serializer, items))
        }
        Unit
    }

    suspend fun get(scope: String, key: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        val f = fileFor(scope, key)
        if (!f.isFile) return@withContext null
        runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { root.deleteRecursively() }
        Unit
    }

    private fun fileFor(scope: String, key: String) = File(File(root, safe(scope)), safe(key) + ".json")

    /**
     * A filesystem-safe, collision-resistant filename component. Filtering/truncating identities made
     * distinct server/user scopes collide; a SHA-256 digest keeps metadata caches isolated instead.
     */
    private fun safe(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val DIR = "libcache"
        const val KEY_VIEWS = "views"
        const val KEY_RESUME = "resume"
        fun childrenKey(parentId: String) = "children_$parentId"
    }
}
