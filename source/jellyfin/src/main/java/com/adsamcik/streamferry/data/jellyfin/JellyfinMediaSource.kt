package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.transcode.SourceCapabilities
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.MediaSourceIds

/**
 * The Jellyfin server exposed as a browsable [MediaSource], backed by the existing
 * [MediaLibraryRepository]. Items it returns already carry the default Jellyfin source id. Jellyfin can
 * transcode server-side, so [capabilities] enables the server-transcode route.
 */
class JellyfinMediaSource(
    private val library: MediaLibraryRepository,
) : MediaSource {
    override val id: String = MediaSourceIds.REMOTE
    override val displayName: String = "Jellyfin"
    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            canServerTranscode = true,
            isSeekable = true,
            isReopenable = true,
            // The current phone transcoder accepts local URI/file inputs. A remote authenticated,
            // reopenable segment input is deliberately not claimed until seeking/thermal/receiver
            // validation exists; Jellyfin's server transcode remains the supported online fallback.
            canStreamToClientTranscoder = false,
        )

    override suspend fun roots(): Result<List<MediaItem>> = library.videoLibraries()
    override suspend fun children(parentId: String): Result<List<MediaItem>> = library.children(parentId)
    override suspend fun item(itemId: String): Result<MediaItem> = library.item(itemId)
    override suspend fun search(query: String): Result<List<MediaItem>> = library.search(query)
    override suspend fun continueWatching(): Result<List<MediaItem>> = library.continueWatching()
}
