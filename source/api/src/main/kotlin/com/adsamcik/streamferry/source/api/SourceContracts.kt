package com.adsamcik.streamferry.source.api

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.segments.MediaSegment
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.MediaItem
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Stable identifier for a source implementation, such as a server protocol or on-device storage. */
@JvmInline
@Serializable
value class SourceProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Source provider id must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies one configured account/server/library independently from other instances of a provider. */
@Serializable
data class SourceInstanceId(
    val provider: SourceProviderId,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Source instance id must not be blank" }
    }

    /** Stable namespace for caches and stores; no credentials or private network locations are included. */
    val storageNamespace: String get() = "${provider.value.length}:${provider.value}:${value.length}:$value"
}

/** Canonical cross-process identity for media owned by a configured source instance. */
@Serializable
data class MediaRef(
    val source: SourceInstanceId,
    val nativeId: String,
) {
    init {
        require(nativeId.isNotBlank()) { "Native media id must not be blank" }
    }

    val storageKey: String get() = "${source.storageNamespace}:${nativeId.length}:$nativeId"
}

data class SourceInstance(
    val id: SourceInstanceId,
    val displayName: String,
)

/** Optional source features. Consumers check these instead of branching on a provider id. */
data class SourceCapabilities(
    val supportsSearch: Boolean = true,
    val supportsContinueWatching: Boolean = false,
    val supportsWatchState: Boolean = false,
    val supportsServerTranscode: Boolean = false,
    val supportsChapters: Boolean = false,
    val supportsSkipSegments: Boolean = false,
    val supportsDownloads: Boolean = false,
    val requiresExternalAuthorization: Boolean = false,
)

interface SourceBackend {
    val identity: SourceInstance
    val capabilities: StateFlow<SourceCapabilities>
    val catalog: CatalogProvider
    val artwork: ArtworkProvider?
    val playback: PlaybackProvider?
    val userState: UserStateProvider?
    val downloads: DownloadProvider?
    val setup: SourceSetupProvider?
}

interface CatalogProvider {
    suspend fun roots(): Result<List<MediaItem>>
    suspend fun children(parent: MediaRef): Result<List<MediaItem>>
    suspend fun item(media: MediaRef): Result<MediaItem>
    suspend fun search(query: String): Result<List<MediaItem>>
    suspend fun continueWatching(): Result<List<MediaItem>> = Result.success(emptyList())
}

/** Opaque, non-secret reference. Only its owning source knows how to turn it into an authenticated fetch. */
@Serializable
data class ArtworkRef(
    val source: SourceInstanceId,
    val opaqueId: String,
)

data class ArtworkRequest(
    val ref: ArtworkRef,
    val maxWidthPx: Int? = null,
    val maxHeightPx: Int? = null,
)

interface ArtworkProvider {
    suspend fun open(request: ArtworkRequest): Result<ArtworkResponse>
}

class ArtworkResponse(
    val contentType: String,
    val contentLength: Long?,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

sealed interface SetupStep {
    data object ServerAddress : SetupStep
    data object Credentials : SetupStep
    data object DeviceCode : SetupStep
    data object BrowserAuthorization : SetupStep
    data object ServerSelection : SetupStep
    data object FolderSelection : SetupStep
    data object Complete : SetupStep
}

data class SetupState(
    val step: SetupStep,
    val redactedMessage: String? = null,
)

sealed interface SetupInput {
    data class Address(val value: String, val insecureTransportApproved: Boolean) : SetupInput
    data class Credentials(val username: String, val password: String) : SetupInput
    data class DeviceCode(val code: String) : SetupInput
    data class Selection(val id: String) : SetupInput
    data object Continue : SetupInput
    data object Cancel : SetupInput
}

interface SourceSetupProvider {
    val state: StateFlow<SetupState>
    suspend fun submit(input: SetupInput): Result<SetupState>
}

data class PlaybackRequest(
    val media: MediaRef,
    val target: TargetCapabilities,
    val maxBitrateBps: Long? = null,
    val forceTranscode: Boolean = false,
    val allowSubtitleBurnIn: Boolean = true,
    val audioTrack: TrackRef? = null,
    val subtitleTrack: TrackRef? = null,
    val startPositionSeconds: Long = 0,
    val maxVideoHeight: Int = 0,
    val preferredVideoCodec: String? = null,
)

@Serializable
data class TrackRef(val opaqueId: String)

data class MediaTrack(
    val ref: TrackRef,
    val language: String?,
    val label: String,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

data class PlaybackDescriptor(
    val media: MediaRef,
    val profile: MediaProfile,
    val runtimeSeconds: Long?,
    val sourceBitrateBps: Long? = null,
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val stream: StreamDescriptor,
)

enum class HlsSegmentFormat { MPEG2_TS, FMP4 }

data class StreamDescriptor(
    val contentType: String,
    val outputContainer: String,
    val hlsSegmentFormat: HlsSegmentFormat? = null,
    val isTranscoding: Boolean,
    val isByteSeekable: Boolean,
    val totalLength: Long?,
) {
    init {
        require(!isTranscoding || !isByteSeekable) {
            "Live transcoded streams must not advertise byte seeking"
        }
    }

    val isHls: Boolean get() = hlsSegmentFormat != null
}

interface PlaybackProvider {
    suspend fun prepare(request: PlaybackRequest): Result<ProviderPlaybackSession>
}

interface ProviderPlaybackSession {
    val descriptor: PlaybackDescriptor
    val upstream: StreamLease
    suspend fun report(event: PlaybackEvent)
    suspend fun replan(change: PlaybackChange): Result<ProviderPlaybackSession>
    suspend fun mediaSegments(): List<MediaSegment> = emptyList()
    suspend fun close(reason: PlaybackStopReason)
}

sealed interface PlaybackEvent {
    data class Started(val positionSeconds: Long) : PlaybackEvent
    data class Progress(val positionSeconds: Long, val isPaused: Boolean) : PlaybackEvent
    data class Stopped(val positionSeconds: Long) : PlaybackEvent
}

data class PlaybackChange(
    val positionSeconds: Long,
    val maxBitrateBps: Long? = null,
    val forceTranscode: Boolean? = null,
    val audioTrack: TrackRef? = null,
    val subtitleTrack: TrackRef? = null,
    val preferredVideoCodec: String? = null,
)

enum class PlaybackStopReason { USER, COMPLETED, REPLACED, ERROR, SHUTDOWN }

/**
 * Credential-isolating access to provider bytes. The provider retains private locations, headers,
 * tokens, session parameters, and origin validation; the gateway receives only opened responses.
 */
interface StreamLease {
    val descriptor: StreamDescriptor
    suspend fun open(range: ByteRange? = null): Result<StreamResponse>
    suspend fun resolve(playlistReference: String): Result<StreamResourceRef?>
    suspend fun open(resource: StreamResourceRef, range: ByteRange? = null): Result<StreamResponse>
}

@Serializable
data class StreamResourceRef(val opaqueId: String)

class StreamResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

data class MediaUserState(
    val resumePositionSeconds: Long? = null,
    val played: Boolean = false,
    val unplayedItemCount: Int? = null,
    val playedPercentage: Double? = null,
)

interface UserStateProvider {
    suspend fun update(media: MediaRef, state: MediaUserState): Result<MediaUserState>
}

enum class DownloadQuality { ORIGINAL, HIGH, MEDIUM, LOW }

data class DownloadStream(
    val media: MediaRef,
    val stream: StreamLease,
)

interface DownloadProvider {
    suspend fun prepareDownload(media: MediaRef, quality: DownloadQuality): Result<DownloadStream>
}

class SourceRegistry(backends: Iterable<SourceBackend> = emptyList()) {
    private val sources = LinkedHashMap<SourceInstanceId, SourceBackend>()

    init {
        backends.forEach(::register)
    }

    fun register(backend: SourceBackend) {
        require(sources.putIfAbsent(backend.identity.id, backend) == null) {
            "Source instance already registered: ${backend.identity.id}"
        }
    }

    fun all(): List<SourceBackend> = sources.values.toList()

    fun get(id: SourceInstanceId): SourceBackend? = sources[id]

    fun require(media: MediaRef): SourceBackend =
        sources[media.source] ?: error("No source registered for ${media.source}")
}

/** Minimal redaction-safe diagnostics surface that source implementations may depend on. */
interface DiagnosticSink {
    fun trace(tag: String, message: String) = debug(tag, message)
    fun debug(tag: String, message: String) {}
    fun info(tag: String, message: String) {}
    fun warn(tag: String, message: String, error: Throwable? = null) {}
    fun error(tag: String, message: String, error: Throwable? = null) {}
    fun event(category: String, message: String) {}

    fun d(tag: String, message: String) = debug(tag, message)
    fun i(tag: String, message: String) = info(tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = warn(tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = error(tag, message, error)
}
