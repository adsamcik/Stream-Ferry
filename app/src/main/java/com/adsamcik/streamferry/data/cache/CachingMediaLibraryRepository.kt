package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository

/**
 * Wraps a [MediaLibraryRepository] with the optional [LibraryCache]: when online it fetches fresh and
 * updates the cache; when the fetch fails (offline / server unreachable) it falls back to the cached
 * copy so the user can still browse what they've seen before. Item details are passed through.
 */
class CachingMediaLibraryRepository(
    private val delegate: MediaLibraryRepository,
    private val cache: LibraryCache,
    private val scope: () -> String,
) : MediaLibraryRepository {

    override suspend fun videoLibraries(): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.KEY_VIEWS) { delegate.videoLibraries() }

    override suspend fun children(parentId: String): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.childrenKey(parentId)) { delegate.children(parentId) }

    override suspend fun item(itemId: String): Result<MediaItem> = delegate.item(itemId)

    // Search is dynamic and query-specific; pass straight through (no caching).
    override suspend fun search(query: String): Result<List<MediaItem>> = delegate.search(query)

    override suspend fun continueWatching(): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.KEY_RESUME) { delegate.continueWatching() }

    private suspend fun freshOrCached(key: String, fetch: suspend () -> Result<List<MediaItem>>): Result<List<MediaItem>> {
        // Bind this request to the account that existed when it began. A network request may finish
        // after logout or a server/account switch; never publish that response into the new account's
        // cache (or serve a prior account's fallback to the now-active UI).
        val requestScope = scope()
        val result = fetch()
        if (scope() != requestScope) return result
        return result.fold(
            onSuccess = { items -> cache.put(requestScope, key, items); result },
            onFailure = { e ->
                // Never mask an expired session (401) with stale cache — the ViewModel must see the
                // auth failure to route the user back to login. Only connectivity failures fall back
                // to the cache so genuine offline browsing still works.
                if (e is JellyfinHttpException && e.isUnauthorized) result
                else cache.get(requestScope, key)?.let { Result.success(it) } ?: result
            },
        )
    }
}
