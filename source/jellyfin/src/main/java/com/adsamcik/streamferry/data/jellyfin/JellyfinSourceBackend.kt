package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.net.TrustedMediaOriginPolicy
import com.adsamcik.streamferry.domain.JellyfinPlaybackReporter
import com.adsamcik.streamferry.domain.JellyfinRepository
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.domain.UpstreamSource
import com.adsamcik.streamferry.source.api.CatalogProvider
import com.adsamcik.streamferry.source.api.HlsSegmentFormat
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.MediaTrack
import com.adsamcik.streamferry.source.api.PlaybackChange
import com.adsamcik.streamferry.source.api.PlaybackDescriptor
import com.adsamcik.streamferry.source.api.PlaybackEvent
import com.adsamcik.streamferry.source.api.PlaybackProvider
import com.adsamcik.streamferry.source.api.PlaybackRequest
import com.adsamcik.streamferry.source.api.PlaybackStopReason
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import com.adsamcik.streamferry.source.api.SourceBackend
import com.adsamcik.streamferry.source.api.SourceCapabilities
import com.adsamcik.streamferry.source.api.SourceInstance
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.SourceProviderId
import com.adsamcik.streamferry.source.api.StreamDescriptor
import com.adsamcik.streamferry.source.api.StreamLease
import com.adsamcik.streamferry.source.api.StreamResourceRef
import com.adsamcik.streamferry.source.api.StreamResponse
import com.adsamcik.streamferry.source.api.TrackRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Provider-neutral registration for one configured server/account runtime. */
class JellyfinSourceBackend(
    override val identity: SourceInstance,
    private val delegate: MediaSource,
    override val playback: PlaybackProvider,
) : SourceBackend, CatalogProvider {

    init {
        require(identity.id.provider == PROVIDER_ID) { "Unexpected provider id for server backend" }
    }

    private val capabilityState = MutableStateFlow(
        SourceCapabilities(
            supportsSearch = true,
            supportsContinueWatching = true,
            supportsWatchState = true,
            supportsServerTranscode = true,
            supportsChapters = true,
            supportsSkipSegments = true,
            supportsDownloads = true,
        ),
    )

    override val capabilities: StateFlow<SourceCapabilities> = capabilityState.asStateFlow()
    override val catalog: CatalogProvider get() = this
    override val artwork = null
    override val userState = null
    override val downloads = null
    override val setup = null

    override suspend fun roots(): Result<List<MediaItem>> = delegate.roots().map(::namespace)

    override suspend fun children(parent: MediaRef): Result<List<MediaItem>> =
        checked(parent) { delegate.children(parent.nativeId).map(::namespace) }

    override suspend fun item(media: MediaRef): Result<MediaItem> =
        checked(media) { delegate.item(media.nativeId).map(::namespace) }

    override suspend fun search(query: String): Result<List<MediaItem>> = delegate.search(query).map(::namespace)

    override suspend fun continueWatching(): Result<List<MediaItem>> =
        delegate.continueWatching().map(::namespace)

    private fun namespace(items: List<MediaItem>): List<MediaItem> = items.map(::namespace)

    private fun namespace(item: MediaItem): MediaItem = item.copy(
        sourceId = PROVIDER_ID.value,
        sourceInstanceId = identity.id,
    )

    private inline fun <T> checked(ref: MediaRef, block: () -> Result<T>): Result<T> =
        if (ref.source == identity.id) block()
        else Result.failure(IllegalArgumentException("Media reference belongs to another source instance"))

    companion object {
        val PROVIDER_ID = SourceProviderId("jellyfin")

        fun identity(serverId: String, userId: String, displayName: String): SourceInstance =
            SourceInstance(
                id = SourceInstanceId(PROVIDER_ID, "$serverId:$userId"),
                displayName = displayName,
            )
    }
}

/** Maps the existing server negotiation/reporting implementation to the session-based source contract. */
class JellyfinPlaybackProvider(
    private val source: SourceInstanceId,
    private val repository: JellyfinRepository,
    private val reporter: JellyfinPlaybackReporter,
    private val httpClient: OkHttpClient,
) : PlaybackProvider {

    init {
        require(source.provider == JellyfinSourceBackend.PROVIDER_ID)
    }

    override suspend fun prepare(request: PlaybackRequest): Result<ProviderPlaybackSession> {
        if (request.media.source != source) {
            return Result.failure(IllegalArgumentException("Media reference belongs to another source instance"))
        }
        val deviceProfile = request.preferredVideoCodec?.let { codec ->
            DeviceProfiles.forTarget(
                caps = request.target,
                maxBitrateBps = request.maxBitrateBps,
                forceTranscode = request.forceTranscode,
                allowSubtitleBurnIn = request.allowSubtitleBurnIn,
                preferredVideoCodec = codec,
                maxVideoHeight = request.maxVideoHeight,
            )
        }
        return repository.playbackInfo(
            itemId = request.media.nativeId,
            capabilities = request.target,
            maxBitrateBps = request.maxBitrateBps,
            forceTranscode = request.forceTranscode,
            allowSubtitleBurnIn = request.allowSubtitleBurnIn,
            audioStreamIndex = request.audioTrack?.opaqueId?.toIntOrNull(),
            subtitleStreamIndex = request.subtitleTrack?.opaqueId?.toIntOrNull(),
            startPositionSeconds = request.startPositionSeconds,
            maxVideoHeight = request.maxVideoHeight,
            deviceProfileOverride = deviceProfile,
        ).mapCatching { info ->
            val upstream = repository.resolveUpstream(info)
            JellyfinProviderPlaybackSession(this, request, info, upstream)
        }
    }

    private inner class JellyfinProviderPlaybackSession(
        private val provider: JellyfinPlaybackProvider,
        private val request: PlaybackRequest,
        private val info: PlaybackInfo,
        upstreamSource: UpstreamSource,
    ) : ProviderPlaybackSession {

        override val upstream: StreamLease = JellyfinStreamLease(upstreamSource, httpClient)

        override val descriptor = PlaybackDescriptor(
            media = request.media,
            profile = info.profile,
            runtimeSeconds = info.runtimeSeconds,
            sourceBitrateBps = info.sourceBitrateBps,
            audioTracks = info.audioTracks.map { track ->
                MediaTrack(
                    ref = TrackRef(track.index.toString()),
                    language = track.language,
                    label = track.label,
                    isDefault = track.isDefault,
                    isForced = track.isForced,
                )
            },
            subtitleTracks = info.subtitleTracks.map { track ->
                MediaTrack(
                    ref = TrackRef(track.index.toString()),
                    language = track.language,
                    label = track.label,
                    isDefault = track.isDefault,
                    isForced = track.isForced,
                )
            },
            stream = upstream.descriptor,
        )

        override suspend fun report(event: PlaybackEvent) {
            when (event) {
                is PlaybackEvent.Started -> reporter.reportStart(info, event.positionSeconds)
                is PlaybackEvent.Progress -> reporter.reportProgress(info, event.positionSeconds, event.isPaused)
                is PlaybackEvent.Stopped -> reporter.reportStopped(info, event.positionSeconds)
            }
        }

        override suspend fun replan(change: PlaybackChange): Result<ProviderPlaybackSession> =
            provider.prepare(
                request.copy(
                    startPositionSeconds = change.positionSeconds,
                    maxBitrateBps = change.maxBitrateBps ?: request.maxBitrateBps,
                    forceTranscode = change.forceTranscode ?: request.forceTranscode,
                    audioTrack = change.audioTrack ?: request.audioTrack,
                    subtitleTrack = change.subtitleTrack ?: request.subtitleTrack,
                    preferredVideoCodec = change.preferredVideoCodec ?: request.preferredVideoCodec,
                ),
            )

        override suspend fun mediaSegments() = repository.mediaSegments(request.media.nativeId)

        override suspend fun close(reason: PlaybackStopReason) {
            reporter.stopTranscode(info)
        }
    }
}

/** Keeps every private locator and authorization value inside the source module. */
private class JellyfinStreamLease(
    upstream: UpstreamSource,
    private val httpClient: OkHttpClient,
) : StreamLease {

    private val initialUrl = upstream.url
    private val authorization = upstream.authHeader
    private val origin = requireNotNull(TrustedMediaOriginPolicy.fromBaseUrl(initialUrl)) {
        "Playback origin is not a valid HTTP(S) authority"
    }
    private val resources = ConcurrentHashMap<String, HttpUrl>()

    override val descriptor = StreamDescriptor(
        contentType = upstream.contentType,
        outputContainer = upstream.outputContainer,
        hlsSegmentFormat = when (upstream.hlsSegmentFormat) {
            com.adsamcik.streamferry.domain.HlsSegmentFormat.MPEG2_TS -> HlsSegmentFormat.MPEG2_TS
            com.adsamcik.streamferry.domain.HlsSegmentFormat.FMP4 -> HlsSegmentFormat.FMP4
            null -> null
        },
        isTranscoding = upstream.isTranscoding,
        isByteSeekable = upstream.isByteSeekable,
        totalLength = upstream.totalLength,
    )

    override suspend fun open(range: com.adsamcik.streamferry.core.http.ByteRange?): Result<StreamResponse> =
        openTrusted(requireNotNull(origin.trustedAbsolute(initialUrl)), range)

    override suspend fun resolve(playlistReference: String): Result<StreamResourceRef?> = runCatching {
        val resolved = origin.resolve(playlistReference, requireNotNull(origin.trustedAbsolute(initialUrl)))
            ?: return@runCatching null
        val opaqueId = UUID.randomUUID().toString()
        resources[opaqueId] = resolved
        StreamResourceRef(opaqueId)
    }

    override suspend fun open(
        resource: StreamResourceRef,
        range: com.adsamcik.streamferry.core.http.ByteRange?,
    ): Result<StreamResponse> {
        val url = resources[resource.opaqueId]
            ?: return Result.failure(IllegalArgumentException("Unknown stream resource"))
        return openTrusted(url, range)
    }

    private suspend fun openTrusted(
        url: HttpUrl,
        range: com.adsamcik.streamferry.core.http.ByteRange?,
    ): Result<StreamResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(origin.isTrusted(url)) { "Cross-origin stream request rejected" }
            val request = Request.Builder().url(url).apply {
                authorization?.let { header("Authorization", it) }
                range?.let { header("Range", "bytes=${it.start}-${it.endInclusive}") }
            }.build()
            val response = httpClient.newCall(request).execute()
            val body = response.body
            StreamResponse(
                statusCode = response.code,
                headers = SAFE_RESPONSE_HEADERS.mapNotNull { name ->
                    response.header(name)?.let { value -> name to value }
                }.toMap(),
                body = body.byteStream(),
            )
        }
    }

    companion object {
        private val SAFE_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
        )
    }
}
