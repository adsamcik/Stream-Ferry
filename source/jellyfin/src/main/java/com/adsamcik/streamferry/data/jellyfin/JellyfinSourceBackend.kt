package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.net.TrustedMediaOriginPolicy
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.source.api.CatalogProvider
import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.source.api.DownloadProvider
import com.adsamcik.streamferry.source.api.DownloadStream
import com.adsamcik.streamferry.source.api.ArtworkProvider
import com.adsamcik.streamferry.source.api.ArtworkRef
import com.adsamcik.streamferry.source.api.ArtworkRequest
import com.adsamcik.streamferry.source.api.ArtworkResponse
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
import com.adsamcik.streamferry.source.api.StreamResourceKind
import com.adsamcik.streamferry.source.api.StreamResourceRef
import com.adsamcik.streamferry.source.api.StreamResponse
import com.adsamcik.streamferry.source.api.TrackRef
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider-neutral registration for one configured server/account runtime. */
class JellyfinSourceBackend(
    override val identity: SourceInstance,
    private val delegate: MediaSource,
    override val playback: PlaybackProvider,
    private val artworkProvider: JellyfinArtworkProvider,
    override val downloads: DownloadProvider,
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
    override val artwork: ArtworkProvider = artworkProvider
    override val userState = null
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
        // `sourceId` is the legacy UI slot ("remote"); canonical provider/account identity is `ref`.
        sourceInstanceId = identity.id,
        artwork = artworkProvider.poster(item.id),
        chapters = item.chapters.mapIndexed { index, chapter ->
            chapter.copy(artwork = artworkProvider.chapter(item.id, index))
        },
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

/** Prepares provider-owned download bytes; the durable queue never receives a URL or credential. */
class JellyfinDownloadProvider(
    private val source: SourceInstanceId,
    private val repository: JellyfinPlaybackRepository,
    private val httpClient: OkHttpClient,
) : DownloadProvider {

    override suspend fun prepareDownload(media: MediaRef, format: DownloadFormat): Result<DownloadStream> {
        if (media.source != source) {
            return Result.failure(IllegalArgumentException("Media reference belongs to another source instance"))
        }
        val infoResult = when (format) {
            DownloadFormat.Original -> repository.playbackInfo(
                itemId = media.nativeId,
                capabilities = DOWNLOAD_CAPABILITIES,
                maxBitrateBps = null,
                forceTranscode = false,
                allowSubtitleBurnIn = false,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                startPositionSeconds = 0,
            )
            is DownloadFormat.Transcode -> repository.playbackInfo(
                itemId = media.nativeId,
                capabilities = DOWNLOAD_CAPABILITIES,
                maxBitrateBps = format.maxBitrateBps,
                forceTranscode = true,
                allowSubtitleBurnIn = false,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                startPositionSeconds = 0,
                downloadProfile = JellyfinDownloadTranscodeProfile(
                    maxBitrateBps = format.maxBitrateBps,
                    container = format.container,
                    videoCodec = format.videoCodec,
                    audioCodec = format.audioCodec,
                ),
            )
        }
        return infoResult.mapCatching { info ->
            val upstream = repository.resolveUpstream(info)
            require(!upstream.isHls) { "This title can only be streamed, not downloaded." }
            DownloadStream(
                media = media,
                stream = JellyfinStreamLease(upstream, httpClient),
                container = info.profile.container,
                runtimeSeconds = info.runtimeSeconds,
            )
        }
    }

    private companion object {
        val DOWNLOAD_CAPABILITIES = TargetCapabilities(
            protocol = Protocol.CAST,
            supportedContainers = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v"),
            supportedVideoCodecs = setOf("h264", "hevc", "h265", "vp9", "vp8", "av1", "mpeg4", "mpeg2video"),
            supportedAudioCodecs = setOf("aac", "ac3", "eac3", "mp3", "opus", "flac", "vorbis", "dts", "truehd", "pcm"),
            supportsHevc = true,
            supports10Bit = true,
            supportsHls = false,
        )
    }
}

/** Authenticated artwork access whose private URL and credential never cross the source boundary. */
class JellyfinArtworkProvider(
    private val source: SourceInstanceId,
    private val client: JellyfinClient,
    httpClient: OkHttpClient,
) : ArtworkProvider {

    private val imageClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun poster(itemId: String): ArtworkRef? = client.posterImageTag(itemId)?.let { imageTag ->
        posterForTag(itemId, imageTag)
    }

    fun chapter(itemId: String, chapterIndex: Int): ArtworkRef? =
        client.chapterImageTag(itemId, chapterIndex)?.let { imageTag ->
            chapterForTag(itemId, chapterIndex, imageTag)
        }

    internal fun posterForTag(itemId: String, imageTag: String): ArtworkRef =
        ArtworkRef(source, encode(KIND_POSTER, itemId, imageTag))

    internal fun chapterForTag(itemId: String, chapterIndex: Int, imageTag: String): ArtworkRef =
        ArtworkRef(source, encode(KIND_CHAPTER, itemId, chapterIndex.toString(), imageTag))

    override suspend fun open(request: ArtworkRequest): Result<ArtworkResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(request.ref.source == source) { "Artwork belongs to another source instance" }
            val fields = decode(request.ref.opaqueId)
            val width = request.maxWidthPx
            val urlText = when (fields.firstOrNull()) {
                KIND_POSTER -> {
                    require(fields.size == 3) { "Invalid poster artwork reference" }
                    client.posterUrl(fields[1], fields[2], width ?: DEFAULT_POSTER_WIDTH_PX)
                }
                KIND_CHAPTER -> {
                    require(fields.size == 4) { "Invalid chapter artwork reference" }
                    client.chapterImageUrl(
                        fields[1],
                        fields[2].toInt(),
                        fields[3],
                        width ?: DEFAULT_CHAPTER_WIDTH_PX,
                    )
                }
                else -> error("Unknown artwork reference")
            } ?: error("Artwork source is not configured")
            val url = urlText.toHttpUrlOrNull() ?: error("Artwork URL is invalid")
            require(client.isTrustedServerUrl(url)) { "Artwork origin is not trusted" }

            val httpRequest = Request.Builder().url(url).apply {
                client.imageAuthHeader()?.let { header("Authorization", it) }
            }.build()
            val response = imageClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                response.close()
                error("Artwork request failed with HTTP ${response.code}")
            }
            val body = response.body
            ArtworkResponse(
                contentType = body.contentType()?.toString() ?: "application/octet-stream",
                contentLength = body.contentLength().takeIf { it >= 0L },
                body = body.byteStream(),
            )
        }
    }

    private fun encode(vararg fields: String): String = fields.joinToString(SEPARATOR) { field ->
        ENCODER.encodeToString(field.toByteArray(Charsets.UTF_8))
    }

    private fun decode(value: String): List<String> = value.split(SEPARATOR).map { field ->
        String(DECODER.decode(field), Charsets.UTF_8)
    }

    companion object {
        private const val KIND_POSTER = "poster"
        private const val KIND_CHAPTER = "chapter"
        private const val SEPARATOR = "."
        private const val DEFAULT_POSTER_WIDTH_PX = 512
        private const val DEFAULT_CHAPTER_WIDTH_PX = 320
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
    }
}

/** Maps the existing server negotiation/reporting implementation to the session-based source contract. */
class JellyfinPlaybackProvider(
    private val source: SourceInstanceId,
    private val repository: JellyfinPlaybackRepository,
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
        return repository.playbackInfo(
            itemId = request.media.nativeId,
            capabilities = request.target,
            maxBitrateBps = request.maxBitrateBps,
            forceTranscode = request.forceTranscode,
            allowSubtitleBurnIn = request.allowSubtitleBurnIn,
            audioStreamIndex = request.audioTrack?.opaqueId?.toIntOrNull(),
            subtitleStreamIndex = if (request.subtitlesDisabled) -1 else request.subtitleTrack?.opaqueId?.toIntOrNull(),
            startPositionSeconds = request.startPositionSeconds,
            maxVideoHeight = request.maxVideoHeight,
            preferredVideoCodec = request.preferredVideoCodec,
        ).mapCatching { info ->
            val upstream = repository.resolveUpstream(info)
            JellyfinProviderPlaybackSession(this, request, info, upstream)
        }
    }

    private inner class JellyfinProviderPlaybackSession(
        private val provider: JellyfinPlaybackProvider,
        private val request: PlaybackRequest,
        private val info: JellyfinPlaybackInfo,
        upstreamSource: JellyfinUpstreamSource,
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
    upstream: JellyfinUpstreamSource,
    httpClient: OkHttpClient,
) : StreamLease {

    private val initialUrl = upstream.url
    private val authorization = upstream.authHeader
    private val origin = requireNotNull(TrustedMediaOriginPolicy.fromBaseUrl(initialUrl)) {
        "Playback origin is not a valid HTTP(S) authority"
    }
    private val resources = ConcurrentHashMap<String, HttpUrl>()
    private val streamClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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

    override suspend fun resolve(
        playlistReference: String,
        fromPlaylist: StreamResourceRef?,
    ): Result<StreamResourceRef?> = runCatching {
        val base = fromPlaylist?.let { resources[it.opaqueId] }
            ?: requireNotNull(origin.trustedAbsolute(initialUrl))
        val resolved = origin.resolve(playlistReference, base)
            ?: return@runCatching null
        val opaqueId = UUID.randomUUID().toString()
        resources[opaqueId] = resolved
        StreamResourceRef(
            opaqueId = opaqueId,
            kind = if (resolved.encodedPath.endsWith(".m3u8", ignoreCase = true)) {
                StreamResourceKind.PLAYLIST
            } else {
                StreamResourceKind.MEDIA
            },
        )
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
            var target = url
            repeat(MAX_REDIRECTS + 1) {
                require(origin.isTrusted(target)) { "Cross-origin stream request rejected" }
                val request = Request.Builder().url(target).apply {
                    authorization?.let { header("Authorization", it) }
                    range?.let {
                        val end = if (it.endInclusive == Long.MAX_VALUE) "" else it.endInclusive.toString()
                        header("Range", "bytes=${it.start}-$end")
                    }
                }.build()
                val response = streamClient.newCall(request).execute()
                if (!response.isRedirect) {
                    return@runCatching StreamResponse(
                        statusCode = response.code,
                        headers = SAFE_RESPONSE_HEADERS.mapNotNull { name ->
                            response.header(name)?.let { value -> name to value }
                        }.toMap(),
                        body = response.body.byteStream(),
                    )
                }
                val next = response.header("Location")?.let { location -> origin.resolve(location, target) }
                response.close()
                target = next ?: error("Cross-origin stream redirect rejected")
            }
            error("Too many stream redirects")
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 3
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
