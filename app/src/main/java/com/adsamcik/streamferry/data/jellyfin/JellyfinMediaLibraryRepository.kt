package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.resilience.LibraryPagingPolicy
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import java.util.Locale

/**
 * [MediaLibraryRepository] over [JellyfinClient] (§8 browse). Lists the user's video libraries, walks
 * a folder's children with the bounded [LibraryPagingPolicy] (so even a large library loads reliably
 * over a slow link), and fetches single-item details for the detail screen.
 */
class JellyfinMediaLibraryRepository(
    private val client: JellyfinClient,
    private val logger: DiagnosticsLogger,
) : MediaLibraryRepository {

    override suspend fun videoLibraries(): Result<List<MediaItem>> = runCatching {
        client.userViews().filter { isVideoLibrary(it.type) }
            .also { logger.event("library", "Loaded ${it.size} video libraries") }
    }.onFailure { logger.w("library", "Failed to load video libraries", it) }

    override suspend fun children(parentId: String): Result<List<MediaItem>> = runCatching {
        val paging = LibraryPagingPolicy()
        val all = ArrayList<MediaItem>()
        var request: LibraryPagingPolicy.PageRequest? = paging.firstPage()
        while (request != null) {
            val req = request
            val page = client.itemsPage(parentId, req.startIndex, req.limit)
            all += page.items
            request = paging.nextPage(req, page.items.size, page.totalRecordCount)
        }
        val snapshot = canonicalizeJellyfinChildren(all)
        logger.event("library", "Loaded ${snapshot.size} unique items under a folder")
        snapshot
    }.onFailure { logger.w("library", "Failed to load folder items", it) }

    override suspend fun item(itemId: String): Result<MediaItem> =
        runCatching { client.itemDetails(itemId) }
            .onFailure { logger.w("library", "Failed to load item details", it) }

    override suspend fun search(query: String): Result<List<MediaItem>> = runCatching {
        client.searchItems(query, SEARCH_LIMIT).items
            .also { logger.event("library", "Search returned ${it.size} result(s)") }
    }.onFailure { logger.w("library", "Search failed", it) }

    override suspend fun continueWatching(): Result<List<MediaItem>> = runCatching {
        client.resumeItems(RESUME_LIMIT)
            .also { logger.event("library", "Loaded ${it.size} continue-watching item(s)") }
    }.onFailure { logger.w("library", "Failed to load continue-watching", it) }

    /** Video collections only; music/books/photos/live-tv/playlists are filtered out. */
    private fun isVideoLibrary(type: String?): Boolean {
        val t = type?.lowercase() ?: return true // unknown collection type: keep (likely mixed video)
        return t !in NON_VIDEO_COLLECTIONS
    }

    private companion object {
        val NON_VIDEO_COLLECTIONS = setOf("music", "musicvideos", "books", "photos", "livetv", "playlists", "audiobooks")
        const val SEARCH_LIMIT = 60
        const val RESUME_LIMIT = 16
    }
}

/**
 * Produces one stable, complete browse snapshot after the Jellyfin page walk finishes. Offset pages can
 * overlap when a server is scanning or an item changes sort position mid-request; keep the first server
 * occurrence of each id and never expose those transient duplicates to a lazy list. Seasons additionally
 * use Jellyfin's numeric index so Season 20 cannot move ahead of Season 3 because of lexical titles.
 */
internal fun canonicalizeJellyfinChildren(items: Iterable<MediaItem>): List<MediaItem> {
    val unique = LinkedHashMap<String, MediaItem>()
    items.forEach { item -> unique.putIfAbsent(item.id, item) }
    val snapshot = unique.values.toList()
    return if (snapshot.isNotEmpty() && snapshot.all { it.type.equals("Season", ignoreCase = true) }) {
        snapshot.sortedWith(
            compareBy<MediaItem>(
                { it.indexNumber ?: Int.MAX_VALUE },
                { it.title.lowercase(Locale.ROOT) },
                { it.id },
            ),
        )
    } else {
        snapshot
    }
}
