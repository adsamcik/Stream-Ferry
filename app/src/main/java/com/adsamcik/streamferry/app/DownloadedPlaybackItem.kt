package com.adsamcik.streamferry.app

import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds

/** A downloaded selection owns its identity even when older indexes lack gallery metadata. */
internal fun DownloadEntry.playbackItem(): MediaItem = mediaItem?.takeIf { it.id == itemId }
    ?.copy(sourceId = MediaSourceIds.REMOTE)
    ?: MediaItem(
        id = itemId, title = title, year = null, overview = null, runtimeSeconds = runtimeSeconds,
        artwork = null, resumePositionSeconds = null, isFolder = false,
        sourceId = MediaSourceIds.REMOTE,
    )
