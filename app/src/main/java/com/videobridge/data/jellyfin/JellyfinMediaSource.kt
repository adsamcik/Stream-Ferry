package com.videobridge.data.jellyfin

import com.videobridge.core.transcode.SourceCapabilities
import com.videobridge.domain.MediaItem
import com.videobridge.domain.MediaLibraryRepository
import com.videobridge.domain.MediaSource
import com.videobridge.domain.MediaSourceIds

/**
 * The Jellyfin server exposed as a browsable [MediaSource], backed by the existing
 * [MediaLibraryRepository]. Items it returns already carry the default Jellyfin source id. Jellyfin can
 * transcode server-side, so [capabilities] enables the server-transcode route.
 */
class JellyfinMediaSource(
    private val library: MediaLibraryRepository,
) : MediaSource {
    override val id: String = MediaSourceIds.JELLYFIN
    override val displayName: String = "Jellyfin"
    override val capabilities: SourceCapabilities =
        SourceCapabilities(canServerTranscode = true, isSeekable = true)

    override suspend fun roots(): Result<List<MediaItem>> = library.videoLibraries()
    override suspend fun children(parentId: String): Result<List<MediaItem>> = library.children(parentId)
    override suspend fun item(itemId: String): Result<MediaItem> = library.item(itemId)
    override suspend fun search(query: String): Result<List<MediaItem>> = library.search(query)
    override suspend fun continueWatching(): Result<List<MediaItem>> = library.continueWatching()
}
