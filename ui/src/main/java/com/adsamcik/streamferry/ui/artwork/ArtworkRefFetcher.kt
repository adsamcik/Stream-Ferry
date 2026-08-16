package com.adsamcik.streamferry.ui.artwork

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.adsamcik.streamferry.source.api.ArtworkRef
import com.adsamcik.streamferry.source.api.ArtworkRequest
import com.adsamcik.streamferry.source.api.SourceRegistry
import okio.buffer
import okio.source

/** Resolves opaque artwork through its owning source without exposing an upstream URL to Compose. */
class ArtworkRefFetcher(
    private val data: ArtworkRef,
    private val options: Options,
    private val sourceRegistry: () -> SourceRegistry,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val provider = sourceRegistry().get(data.source)?.artwork ?: return null
        val response = provider.open(ArtworkRequest(data)).getOrThrow()
        return SourceResult(
            source = ImageSource(response.body.source().buffer(), options.context),
            mimeType = response.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val sourceRegistry: () -> SourceRegistry,
    ) : Fetcher.Factory<ArtworkRef> {
        override fun create(data: ArtworkRef, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkRefFetcher(data, options, sourceRegistry)
    }
}
