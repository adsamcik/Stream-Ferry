package com.adsamcik.streamferry.data.local

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.source.api.CatalogProvider
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.SourceBackend
import com.adsamcik.streamferry.source.api.SourceCapabilities
import com.adsamcik.streamferry.source.api.SourceInstance
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.SourceProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider-neutral registration adapter for the phone's configured local-media library. */
class LocalSourceBackend(
    private val delegate: LocalMediaSource,
    instanceValue: String = DEFAULT_INSTANCE,
) : SourceBackend, CatalogProvider {

    override val identity = SourceInstance(
        id = SourceInstanceId(PROVIDER_ID, instanceValue),
        displayName = delegate.displayName,
    )

    private val capabilityState = MutableStateFlow(
        SourceCapabilities(
            supportsSearch = true,
            supportsContinueWatching = false,
            supportsWatchState = false,
            supportsServerTranscode = false,
            supportsChapters = false,
            supportsSkipSegments = false,
            supportsDownloads = false,
        ),
    )

    override val capabilities: StateFlow<SourceCapabilities> = capabilityState.asStateFlow()
    override val catalog: CatalogProvider get() = this
    override val artwork = null
    override val playback = null
    override val userState = null
    override val downloads = null
    override val setup = null

    override suspend fun roots(): Result<List<MediaItem>> = delegate.roots().map(::namespace)

    override suspend fun children(parent: MediaRef): Result<List<MediaItem>> =
        checked(parent) { delegate.children(parent.nativeId).map(::namespace) }

    override suspend fun item(media: MediaRef): Result<MediaItem> =
        checked(media) { delegate.item(media.nativeId).map(::namespace) }

    override suspend fun search(query: String): Result<List<MediaItem>> = delegate.search(query).map(::namespace)

    private fun namespace(items: List<MediaItem>): List<MediaItem> = items.map(::namespace)

    private fun namespace(item: MediaItem): MediaItem = item.copy(
        sourceId = PROVIDER_ID.value,
        sourceInstanceId = identity.id,
    )

    private inline fun <T> checked(ref: MediaRef, block: () -> Result<T>): Result<T> =
        if (ref.source == identity.id) block()
        else Result.failure(IllegalArgumentException("Media reference belongs to another source instance"))

    companion object {
        val PROVIDER_ID = SourceProviderId("local")
        const val DEFAULT_INSTANCE = "phone-library"
    }
}
