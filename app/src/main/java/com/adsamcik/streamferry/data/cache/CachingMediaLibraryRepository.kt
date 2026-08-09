package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import java.io.IOException

/**
 * Wraps a [MediaLibraryRepository] with account-scoped, persistent metadata caching. A tokenless cached
 * session can read only local metadata: [isLiveSession] must be true before this wrapper calls [delegate].
 * That boundary prevents an offline app launch from attaching a saved token to an unverified origin.
 */
class CachingMediaLibraryRepository(
    private val delegate: MediaLibraryRepository,
    private val cache: LibraryCache,
    private val scope: () -> String,
    private val isLiveSession: () -> Boolean = { true },
    private val connectionMonitor: JellyfinConnectionMonitor? = null,
) : MediaLibraryRepository {

    override suspend fun videoLibraries(): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.KEY_VIEWS) { delegate.videoLibraries() }

    override suspend fun children(parentId: String): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.childrenKey(parentId)) { delegate.children(parentId) }

    override suspend fun item(itemId: String): Result<MediaItem> {
        val requestScope = scope()
        if (!isLiveSession()) {
            return cachedItem(requestScope, itemId)
        }
        val remoteWrite = cache.beginRemoteWrite(requestScope)
        val result = delegate.item(itemId)
        if (scope() != requestScope) return Result.failure(OfflineCacheScopeChangedException())
        return result.fold(
            onSuccess = { item ->
                val cached = cache.put(remoteWrite, itemKey(itemId), listOf(item))
                connectionMonitor?.markOnline()
                Result.success(cached.singleOrNull() ?: item)
            },
            onFailure = { error ->
                if (isConnectivityFailure(error)) {
                    connectionMonitor?.markUnavailable()
                    cachedItem(requestScope, itemId, error)
                } else {
                    result
                }
            },
        )
    }

    /**
     * Search always includes local indexed metadata. When online, remote results refresh that index and
     * are unioned with it so recently browsed cached titles remain findable while server search is partial.
     */
    override suspend fun search(query: String): Result<List<MediaItem>> {
        val requestScope = scope()
        val local = cache.search(requestScope, query)
        if (!isLiveSession()) {
            return if (scope() == requestScope) Result.success(local)
            else Result.failure(OfflineCacheScopeChangedException())
        }
        val remoteWrite = cache.beginRemoteWrite(requestScope)
        val result = delegate.search(query)
        if (scope() != requestScope) return Result.failure(OfflineCacheScopeChangedException())
        return result.fold(
            onSuccess = { remote ->
                val cached = cache.put(remoteWrite, searchKey(query), remote)
                connectionMonitor?.markOnline()
                Result.success(mergeById(cached, local))
            },
            onFailure = { error ->
                if (isConnectivityFailure(error)) {
                    connectionMonitor?.markUnavailable()
                    Result.success(local)
                } else {
                    result
                }
            },
        )
    }

    override suspend fun continueWatching(): Result<List<MediaItem>> =
        freshOrCached(LibraryCache.KEY_RESUME) { delegate.continueWatching() }

    private suspend fun freshOrCached(
        key: String,
        fetch: suspend () -> Result<List<MediaItem>>,
    ): Result<List<MediaItem>> {
        // Bind this request to the account that existed when it began. A remote request may finish after
        // logout or a server/account switch; never publish it into, or fall back from, another account.
        val requestScope = scope()
        if (!isLiveSession()) return cachedList(requestScope, key)
        val remoteWrite = cache.beginRemoteWrite(requestScope)
        val result = fetch()
        if (scope() != requestScope) return Result.failure(OfflineCacheScopeChangedException())
        return result.fold(
            onSuccess = { items ->
                val cached = cache.put(remoteWrite, key, items)
                connectionMonitor?.markOnline()
                Result.success(cached)
            },
            onFailure = { error ->
                // Do not conceal an expired/revoked token or a definitive 4xx/removed item behind stale
                // data. Only transport failures and retryable server failures are legitimate offline
                // fallback conditions.
                if (isConnectivityFailure(error)) {
                    connectionMonitor?.markUnavailable()
                    cachedList(requestScope, key, error)
                } else {
                    result
                }
            },
        )
    }

    private suspend fun cachedList(
        requestScope: String,
        key: String,
        originalError: Throwable? = null,
    ): Result<List<MediaItem>> {
        val cached = cache.get(requestScope, key)
        if (scope() != requestScope) {
            return Result.failure(OfflineCacheScopeChangedException())
        }
        return cached?.let { Result.success(it) }
            ?: Result.failure(originalError ?: JellyfinOfflineCacheMissException())
    }

    private suspend fun cachedItem(
        requestScope: String,
        itemId: String,
        originalError: Throwable? = null,
    ): Result<MediaItem> {
        val cached = cache.item(requestScope, itemId)
        if (scope() != requestScope) {
            return Result.failure(OfflineCacheScopeChangedException())
        }
        return cached?.let { Result.success(it) }
            ?: Result.failure(originalError ?: JellyfinOfflineCacheMissException())
    }

    private fun isConnectivityFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is IOException ||
                (cause is JellyfinHttpException && !cause.isUnauthorized && UpstreamRetry.isRetryableStatus(cause.code))
        }

    private fun mergeById(first: List<MediaItem>, second: List<MediaItem>): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        first.forEach { byId[it.id] = it }
        second.forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }

    private fun itemKey(itemId: String) = "item_$itemId"
    private fun searchKey(query: String) = "search_${query.trim().lowercase()}"
}

/** No local metadata exists for this server/account/item while Jellyfin cannot be contacted. */
class JellyfinOfflineCacheMissException : IllegalStateException("Jellyfin is unavailable and this item is not cached.")

/** The active profile changed during a cache read, so retaining the prior account's result is unsafe. */
private class OfflineCacheScopeChangedException : IllegalStateException("The active Jellyfin profile changed.")