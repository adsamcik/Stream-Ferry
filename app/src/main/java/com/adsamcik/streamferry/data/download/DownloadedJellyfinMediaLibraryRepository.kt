package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import com.adsamcik.streamferry.domain.MediaSourceIds
import java.util.Locale

/**
 * Adds verified, owner-scoped offline copies to the Jellyfin browse surface. The shelf is virtual rather
 * than pretending downloaded files still have a reachable Jellyfin parent folder: it stays findable at
 * the Jellyfin root even when only a partial gallery was cached, and it never exposes another account's
 * download metadata.
 */
class DownloadedJellyfinMediaLibraryRepository(
    private val delegate: MediaLibraryRepository,
    private val downloadStore: DownloadStore,
    private val owner: () -> DownloadOwner?,
) : MediaLibraryRepository {

    override suspend fun videoLibraries(): Result<List<MediaItem>> {
        val requestOwner = owner()
        val downloaded = downloadedItems(requestOwner)
        return delegate.videoLibraries().fold(
            onSuccess = { libraries ->
                if (owner() != requestOwner) Result.failure(DownloadedScopeChangedException())
                else Result.success(withDownloadedShelf(libraries, downloaded))
            },
            onFailure = { error ->
                if (owner() != requestOwner) Result.failure(DownloadedScopeChangedException())
                else if (downloaded.isNotEmpty()) Result.success(listOf(downloadedShelf(downloaded.size)))
                else Result.failure(error)
            },
        )
    }

    override suspend fun children(parentId: String): Result<List<MediaItem>> {
        if (parentId != OFFLINE_DOWNLOADS_ROOT_ID) return delegate.children(parentId)
        val requestOwner = owner()
        val downloaded = downloadedItems(requestOwner)
        return if (owner() == requestOwner) Result.success(downloaded)
        else Result.failure(DownloadedScopeChangedException())
    }

    override suspend fun item(itemId: String): Result<MediaItem> {
        val requestOwner = owner()
        if (itemId == OFFLINE_DOWNLOADS_ROOT_ID) {
            val downloaded = downloadedItems(requestOwner)
            if (owner() != requestOwner) return Result.failure(DownloadedScopeChangedException())
            return downloaded.takeIf { it.isNotEmpty() }
                ?.let { Result.success(downloadedShelf(it.size)) }
                ?: Result.failure(IllegalStateException("No Jellyfin downloads are available offline."))
        }
        val result = delegate.item(itemId)
        if (owner() != requestOwner) return Result.failure(DownloadedScopeChangedException())
        return result.fold(
            onSuccess = { item -> Result.success(item) },
            onFailure = { error ->
                downloadedItems(requestOwner).firstOrNull { it.id == itemId }?.let { Result.success(it) }
                    ?: Result.failure(error)
            },
        )
    }

    override suspend fun search(query: String): Result<List<MediaItem>> {
        val requestOwner = owner()
        val downloaded = downloadedItems(requestOwner).filter { it.matches(query) }
        return delegate.search(query).fold(
            onSuccess = { remote ->
                if (owner() != requestOwner) Result.failure(DownloadedScopeChangedException())
                else Result.success(mergeById(remote, downloaded))
            },
            onFailure = { error ->
                if (owner() != requestOwner) Result.failure(DownloadedScopeChangedException())
                else if (downloaded.isNotEmpty()) Result.success(downloaded) else Result.failure(error)
            },
        )
    }

    override suspend fun continueWatching(): Result<List<MediaItem>> = delegate.continueWatching()

    private suspend fun downloadedItems(expectedOwner: DownloadOwner? = owner()): List<MediaItem> {
        // Ownerless legacy entries remain intact on disk but cannot be safely attributed to a Jellyfin
        // server/user, so an explicit migration is required before they can appear in this scoped gallery.
        val activeOwner = expectedOwner ?: return emptyList()
        val items = downloadStore.listForOwner(activeOwner)
            .map { entry -> entry.asGalleryItem() }
            .sortedWith(compareBy<MediaItem>({ it.title.lowercase(Locale.ROOT) }, { it.id }))
        return if (owner() == activeOwner) items else emptyList()
    }

    private fun withDownloadedShelf(libraries: List<MediaItem>, downloaded: List<MediaItem>): List<MediaItem> {
        if (downloaded.isEmpty()) return libraries
        return listOf(downloadedShelf(downloaded.size)) + libraries.filterNot { it.id == OFFLINE_DOWNLOADS_ROOT_ID }
    }

    private fun downloadedShelf(count: Int): MediaItem = MediaItem(
        id = OFFLINE_DOWNLOADS_ROOT_ID,
        title = "Downloaded",
        year = null,
        runtimeSeconds = null,
        overview = "${count.coerceAtLeast(1)} Jellyfin item${if (count == 1) "" else "s"} available offline.",
        resumePositionSeconds = null,
        isFolder = true,
        type = "Folder",
        subtitle = "Available offline",
        sourceId = MediaSourceIds.JELLYFIN,
    )

    private fun DownloadEntry.asGalleryItem(): MediaItem = mediaItem
        ?.takeIf { it.id == itemId }
        ?.copy(sourceId = MediaSourceIds.JELLYFIN)
        ?: MediaItem(
            id = itemId,
            title = title,
            year = null,
            runtimeSeconds = runtimeSeconds,
            overview = null,
            resumePositionSeconds = null,
            isFolder = false,
            type = "Video",
            sourceId = MediaSourceIds.JELLYFIN,
        )

    private fun MediaItem.matches(query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return true
        return listOf(title, subtitle, overview, type)
            .any { value -> value?.lowercase(Locale.ROOT)?.contains(needle) == true }
    }

    private fun mergeById(first: List<MediaItem>, second: List<MediaItem>): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        first.forEach { byId[it.id] = it }
        second.forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.sortedWith(compareBy<MediaItem>({ it.title.lowercase(Locale.ROOT) }, { it.id }))
    }

    /** The active Jellyfin account changed while resolving local downloaded metadata. */
    private class DownloadedScopeChangedException : IllegalStateException("The active Jellyfin profile changed.")

    companion object {
        /** Not a Jellyfin server id: reserved only for Stream Ferry's owner-scoped offline shelf. */
        const val OFFLINE_DOWNLOADS_ROOT_ID = "streamferry:offline-downloads:v1"
    }
}