package com.adsamcik.streamferry.domain

import com.adsamcik.streamferry.core.session.ProxySession
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamDecision
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.transcode.SourceCapabilities
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.ArtworkRef
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.SourceProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Core domain interfaces (§19). The UI depends only on these; it never touches a server protocol, Cast,
 * DLNA, sockets, token storage, URL construction or playback reporting directly.
 */

// ----- Identity / media model (domain-level, decoupled from provider SDK types) -----

data class ServerProfile(
    val id: String,
    val baseUrlRedactedForUi: String,
    val name: String,
    val active: Boolean = false,
    val loggedIn: Boolean = false,
)

data class UserSession(val userId: String, val serverId: String)

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val year: Int?,
    val runtimeSeconds: Long?,
    val overview: String?,
    val resumePositionSeconds: Long?,
    val isFolder: Boolean,
    /** Provider-neutral item classification, e.g. library, series, season, movie, episode, or folder. */
    val type: String? = null,
    /**
     * Numeric position within the item's parent when supplied (for example Season 0, Season 1,
     * …). A complete paged season snapshot uses this to keep its rows in a stable numeric order.
     */
    val indexNumber: Int? = null,
    /** For an Episode, the parent Series id — used to resolve the next episode for autoplay. Null otherwise. */
    val seriesId: String? = null,
    /** Optional secondary line for the gallery (e.g. "Series · S1 E3"); never a secret. */
    val subtitle: String? = null,
    /**
     * Opaque provider artwork id when the item has poster art. Consumers must ask the owning
     * [com.adsamcik.streamferry.source.api.ArtworkProvider] to fetch it.
     */
    val imageTag: String? = null,
    /** Source-owned artwork reference. UI and playback never receive a provider URL or credential. */
    val artwork: ArtworkRef? = null,
    /**
     * Chapter markers ("sections") for the seek-preview scrubber, in ascending start order. Each may
     * carry an opaque chapter image id (shown ONLY in this phone's playback UI while scrubbing — never
     * given to the TV). Empty when the item has no chapters or hasn't been fully loaded yet.
     */
    val chapters: List<MediaChapter> = emptyList(),
    /**
     * Which [MediaSource] this item belongs to (e.g. [MediaSourceIds.REMOTE], [MediaSourceIds.LOCAL]).
     * Legacy source family used while callers migrate to [ref].
     */
    val sourceId: String = MediaSourceIds.REMOTE,
    /** Configured source namespace. Defaults preserve existing serialized media during migration. */
    val sourceInstanceId: SourceInstanceId = SourceInstanceId(SourceProviderId(sourceId), sourceId),
    /** True when the item is fully watched. Always false for sources without user state. */
    val played: Boolean = false,
    /** For a series/season folder, the number of still-unwatched child items; null when not applicable. */
    val unplayedItemCount: Int? = null,
    /** Watched fraction (0..100) of a single item, when the server reports it; null otherwise. */
    val playedPercentage: Double? = null,
) {
    /** Canonical provider/account-safe identity used by new caches, queues, playback, and diagnostics. */
    val ref: MediaRef get() = MediaRef(sourceInstanceId, id)
}

/**
 * A single chapter ("section") of a video. [startSeconds] is where it begins; [imageTag], when present,
 * means the source has a chapter thumbnail for it (addressed by the chapter's index in [MediaItem.chapters]).
 */
@Serializable
data class MediaChapter(
    val startSeconds: Long,
    val name: String?,
    val imageTag: String?,
    /** Source-owned chapter artwork reference, populated at the source-module boundary. */
    val artwork: ArtworkRef? = null,
) {
    val hasImage: Boolean get() = artwork != null || imageTag != null
}

data class PlaybackInfo(
    val mediaSourceId: String,
    val playSessionId: String?,
    val profile: MediaProfile,
    val runtimeSeconds: Long?,
    /** Source bitrate (bits/sec) when reported, used to cap the adaptive bitrate ladder. */
    val sourceBitrateBps: Long? = null,
    /** Selectable audio tracks reported by the server (empty if none/unknown). */
    val audioTracks: List<MediaTrack> = emptyList(),
    /** Selectable subtitle tracks reported by the server (empty if none). "Off" is implicit, not a track. */
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** Catalogue item id used by session reports; distinct from [mediaSourceId] for multi-version media. */
    val itemId: String = mediaSourceId,
)

/**
 * A selectable audio or subtitle stream of the current media (as reported by its source), surfaced so the
 * user can pick a language. [index] is the server stream index passed back as AudioStreamIndex /
 * SubtitleStreamIndex to re-resolve playback with this track.
 */
data class MediaTrack(
    val index: Int,
    /** ISO language code when known (e.g. "eng"), else null. */
    val language: String?,
    /** Human label for the picker (server DisplayTitle/Title, else the language, else "Track N"). */
    val label: String,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

// ----- Media sources (multi-source gallery) -----

/** Stable identifiers for the built-in media sources. */
object MediaSourceIds {
    const val REMOTE = "remote"
    const val LOCAL = "local"
}

/**
 * A browsable source of video [MediaItem]s (remote servers, on-device local files, …). The gallery routes
 * browsing/search through the active source; [capabilities] tells the player whether the source can
 * transcode server-side (otherwise the phone transcodes on-device).
 */
interface MediaSource {
    val id: String
    val displayName: String
    val capabilities: SourceCapabilities

    /** Top-level entries: server libraries, or local granted folders / videos. */
    suspend fun roots(): Result<List<MediaItem>>
    suspend fun children(parentId: String): Result<List<MediaItem>>
    suspend fun item(itemId: String): Result<MediaItem>
    suspend fun search(query: String): Result<List<MediaItem>>

    /**
     * Items the user can resume ("Continue Watching"), most-recently-played first. Defaults to empty for
     * sources without a server-side watch state (e.g. on-device local files).
     */
    suspend fun continueWatching(): Result<List<MediaItem>> = Result.success(emptyList())
}

// ----- Repositories -----

interface AuthRepository {
    val currentUser: Flow<UserSession?>
    suspend fun setServer(rawUrl: String, userApprovedHttp: Boolean): Result<ServerProfile>
    suspend fun testConnection(): Result<String> // returns redacted server version string
    suspend fun login(username: String, password: String): Result<UserSession>
    suspend fun logout()
    suspend fun deleteServerProfile(serverId: String)
    suspend fun deleteAllData()
    /** All known server profiles (kept even when offline); active + loggedIn flags for the picker. */
    suspend fun servers(): List<ServerProfile>
    /** Switch the active server; returns its restored session, or null if it needs login / can't verify. */
    suspend fun switchServer(serverId: String): UserSession?
}

interface MediaLibraryRepository {
    suspend fun videoLibraries(): Result<List<MediaItem>>
    suspend fun children(parentId: String): Result<List<MediaItem>>
    suspend fun item(itemId: String): Result<MediaItem>
    /** Free-text search across all video libraries (recursive). */
    suspend fun search(query: String): Result<List<MediaItem>>
    /** "Continue Watching" items (resume list), most-recently-played first. Defaults to empty. */
    suspend fun continueWatching(): Result<List<MediaItem>> = Result.success(emptyList())
}

data class DownloadTranscodeProfile(
    val maxBitrateBps: Long,
    val container: String,
    val videoCodec: String,
    val audioCodec: String,
)

/** Transitional provider-neutral playback seam used while the gateway adopts [ProviderPlaybackSession]. */
interface ServerPlaybackProvider {
    /**
     * Request official playback info for a target+preferences. Returns a [PlaybackInfo] containing
     * the upstream stream locator INTERNALLY (never surfaced to UI/TV) plus PlaySessionId. The
     * concrete upstream URL is provided to the proxy layer only, via [openUpstream].
     */
    suspend fun playbackInfo(
        itemId: String,
        capabilities: TargetCapabilities,
        maxBitrateBps: Long?,
        forceTranscode: Boolean,
        allowSubtitleBurnIn: Boolean,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        startPositionSeconds: Long,
        maxVideoHeight: Int = 0,
        preferredVideoCodec: String? = null,
        downloadProfile: DownloadTranscodeProfile? = null,
    ): Result<PlaybackInfo>

    /** Resolve the secret upstream URL + auth header for a media source (proxy layer only). */
    suspend fun resolveUpstream(info: PlaybackInfo): UpstreamSource

    /**
     * Skippable media segments (intro/outro/recap/…) exposed by the source
     * (10.10+). Empty when the server/item has none — never an error, so playback is unaffected.
     */
    suspend fun mediaSegments(itemId: String): List<com.adsamcik.streamferry.core.segments.MediaSegment> = emptyList()
}

/** Actual media-segment packaging for an HLS playlist handed to a Cast receiver. */
enum class HlsSegmentFormat {
    /** HLS media is carried in MPEG-2 transport-stream segments. */
    MPEG2_TS,

    /** HLS media is carried in fragmented-MP4 / CMAF segments. */
    FMP4,
}

/**
 * Renderer-facing stream metadata. This describes the bytes behind the phone proxy URL, rather than
 * the original source container. It lets Cast select the correct HLS pipeline and lets DLNA advertise
 * byte seeking only when the proxy can actually provide it.
 */
data class RendererStream(
    val mimeType: String,
    val hlsSegmentFormat: HlsSegmentFormat? = null,
    val isByteSeekable: Boolean = false,
)

/** Secret upstream descriptor — never logged or sent to the TV. */
data class UpstreamSource(
    val url: String,
    val authHeader: String?,
    /** MIME of the phone-proxied resource (the HLS playlist itself for HLS). */
    val contentType: String,
    /** Actual encoded output container reported by the source when transcoding. */
    val outputContainer: String,
    /** Actual HLS segment packaging; null for a progressive resource. */
    val hlsSegmentFormat: HlsSegmentFormat? = null,
    val isHls: Boolean,
    /**
     * True when this is a live server-side transcode (HLS for Cast, progressive for DLNA). A transcode
     * is NOT byte-seekable and is seeked/resumed **server-side** by re-resolving at the target position
     * (the source honours the start request); a direct-play stream is byte-range seekable via the proxy.
     */
    val isTranscoding: Boolean,
    /** True only when the proxy can safely honour byte-range requests for this resource. */
    val isByteSeekable: Boolean,
    /** Byte length of a direct-play entity when known, null for a transcode (unknown live length). */
    val totalLength: Long?,
) {
    init {
        require(isHls == (hlsSegmentFormat != null)) {
            "HLS streams must declare a segment format; progressive streams must not."
        }
        require(!isTranscoding || !isByteSeekable) {
            "Live transcoded streams must not advertise byte seeking."
        }
    }

    /** The complete metadata passed to the Cast or DLNA controller. */
    val rendererStream: RendererStream
        get() = RendererStream(contentType, hlsSegmentFormat, isByteSeekable)
}

// ----- Targets -----

data class DiscoveredTarget(
    val id: String,
    val displayName: String,
    val protocol: Protocol,
    val capabilities: TargetCapabilities,
    val lastTestedStatus: String?,
    /**
     * Ephemeral identity evidence collected during this discovery scan. It is intentionally separate
     * from [id]: a Cast route ID is not a Cast device ID, and a DLNA USN is not necessarily a UDN.
     * Network hosts are discovery-only evidence and must never be persisted.
     */
    val discoveryMetadata: TargetDiscoveryMetadata = TargetDiscoveryMetadata(),
)

/**
 * Protocol-independent identity evidence used only to decide whether two discovered endpoints could
 * be the same physical screen. Values come from documented Cast and DLNA discovery surfaces.
 */
data class TargetDiscoveryMetadata(
    /** Public CastDevice.deviceId, when this is a Cast endpoint. */
    val castDeviceId: String? = null,
    /** UDN from the selected DLNA MediaRenderer device node. */
    val dlnaUdn: String? = null,
    /** SSDP USN from the response that was used to describe this renderer. */
    val dlnaUsn: String? = null,
    /** Host that sent and was validated against the DLNA description, or the Cast device host. */
    val validatedSourceHost: String? = null,
    /** Validated DLNA description host. This is transient matching evidence, never persisted. */
    val validatedDescriptionHost: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    /** True only when discovery resolved a protocol endpoint with renderer-volume support. */
    val volumeControlAvailable: Boolean = false,
)

sealed interface PlaybackTargetEvent {
    data object Connected : PlaybackTargetEvent
    data class StatusChanged(val positionSeconds: Long, val isPlaying: Boolean) : PlaybackTargetEvent
    /** The renderer started/stopped rebuffering (a stall). Feeds the adaptive-bitrate controller. */
    data class BufferingChanged(val isBuffering: Boolean) : PlaybackTargetEvent
    /** A transport command failed without making the playable stream itself terminal (for example resume seek). */
    data class ControlError(val redactedMessage: String) : PlaybackTargetEvent
    /**
     * The renderer reported a playback failure. [kind] drives recovery (see PlaybackRecovery).
     * [qualifiedFormatEvidence] is true only when the renderer supplied an explicit decode or
     * unsupported-source signal. It prevents an ambiguous transport/startup failure from being
     * remembered as a permanent "this container needs transcoding" capability rule.
     */
    data class Error(
        val kind: PlaybackFailureKind,
        val redactedMessage: String,
        val qualifiedFormatEvidence: Boolean = false,
    ) : PlaybackTargetEvent
    data object Ended : PlaybackTargetEvent
    /** The renderer stopped before a credible natural completion; close without watched/autoplay effects. */
    data object Stopped : PlaybackTargetEvent
    data object Disconnected : PlaybackTargetEvent
}

/**
 * Why playback failed on the renderer — classified so recovery can be precise (see PlaybackRecovery):
 * a [FORMAT] failure (the TV can't decode/demux the stream) warrants a transcode fallback, while a
 * [NETWORK] failure warrants retrying the same stream, and [UNKNOWN] is surfaced.
 */
enum class PlaybackFailureKind { FORMAT, NETWORK, UNKNOWN }

/** Abstraction over Cast and DLNA. The controller receives ONLY the phone proxy URL. */
interface PlaybackTargetController {
    val protocol: Protocol
    val events: Flow<PlaybackTargetEvent>
    suspend fun discover(timeoutMillis: Long): List<DiscoveredTarget>
    suspend fun connect(target: DiscoveredTarget)
    /**
     * @param proxyUrl phone proxy URL only. @param stream actual proxied output metadata.
     * @param startPositionSeconds where playback should begin (a resume/reload position). The controller
     *   is responsible for starting AT this offset (Cast sets it in the load request; DLNA seeks once the
     *   renderer is playing), so callers must NOT issue a separate post-load seek — that races the load and
     *   is silently dropped, leaving the TV playing from the start.
     * @param playWhenReady whether the renderer should start after loading. False is used when a paused
     *   progressive transcode is reloaded for an absolute seek, so seeking never unexpectedly resumes it.
     */
    suspend fun load(
        proxyUrl: String,
        stream: RendererStream,
        title: String,
        durationSeconds: Long?,
        startPositionSeconds: Long = 0,
        playWhenReady: Boolean = true,
    )
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionSeconds: Long)
    suspend fun stop()
    /** Current normalized master volume from the renderer, or null when it cannot be read safely. */
    suspend fun readCurrentVolume(): Float? = null
    suspend fun setVolume(level: Float)
    suspend fun disconnect()

    /**
     * Called by the engine just before it tears down and reloads the upstream stream (a transcode seek, an
     * adaptive bitrate switch, or a recovery reload). A polled controller (DLNA) uses this to (a) stop its
     * status poll BEFORE the old stream ends, so a transient "finished" isn't mistaken for end-of-media and
     * doesn't stop the freshly-reloaded stream, and (b) stop the renderer so the subsequent new-stream URI
     * is accepted (many renderers reject a URI change while still PLAYING). Push-based controllers (Cast)
     * need no action (default no-op).
     */
    suspend fun prepareReload() {}

    /**
     * A short, redaction-safe snapshot of the renderer's CURRENT playback state (player state, idle/error
     * reason, what it thinks it's loading), for diagnostics when the startup watchdog fires — so a shared
     * report explains WHY the TV never started (e.g. Cast parked in LOADING, or an idle ERROR reason).
     * Default empty; Cast/DLNA controllers override it.
     */
    suspend fun diagnosticStatus(): String = ""
}

interface SecureTokenStore {
    suspend fun put(serverId: String, token: String)
    suspend fun get(serverId: String): String?
    suspend fun remove(serverId: String)
    suspend fun clear()
}

/** Maps a [ProxySession] to/from a provider play session and a target session (§8). */
interface PlaybackSessionCoordinator {
    val active: Flow<ProxySession?>
    suspend fun start(info: PlaybackInfo, upstream: UpstreamSource, phoneLanIp: String): ProxySession
    suspend fun stop(reason: String)
}

interface ProviderPlaybackReporter {
    suspend fun reportStart(info: PlaybackInfo)
    /** Start reporting at the exact resume point rather than pretending resumed media began at zero. */
    suspend fun reportStart(info: PlaybackInfo, initialPositionSeconds: Long) = reportStart(info)
    suspend fun reportProgress(info: PlaybackInfo, positionSeconds: Long, isPaused: Boolean)
    suspend fun reportStopped(info: PlaybackInfo, positionSeconds: Long)
    /** Ensure server-side transcode/HLS session is torn down (§8 cleanup). */
    suspend fun stopTranscode(info: PlaybackInfo)
}

interface NetworkPermissionManager {
    fun hasLocalNetworkAccess(): Boolean
    fun hasNotificationsPermission(): Boolean
    /** Human-readable local-network access status for diagnostics ("granted" / "denied" / not-enforced). */
    fun localNetworkStatus(): String
}

interface StreamSelectionService {
    fun select(caps: TargetCapabilities, media: MediaProfile, prefs: StreamPreferences): StreamDecision
}
