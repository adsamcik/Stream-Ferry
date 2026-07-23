package com.videobridge.data.cache

import com.videobridge.data.jellyfin.JellyfinHttpException
import com.videobridge.domain.MediaItem
import com.videobridge.domain.MediaLibraryRepository

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
        val result = fetch()
        return result.fold(
            onSuccess = { items -> cache.put(scope(), key, items); result },
            onFailure = { e ->
                // Never mask an expired session (401) with stale cache — the ViewModel must see the
                // auth failure to route the user back to login. Only connectivity failures fall back
                // to the cache so genuine offline browsing still works.
                if (e is JellyfinHttpException && e.isUnauthorized) result
                else cache.get(scope(), key)?.let { Result.success(it) } ?: result
            },
        )
    }
}
