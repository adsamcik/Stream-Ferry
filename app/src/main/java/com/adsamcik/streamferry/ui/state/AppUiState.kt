package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.JellyfinLibraryStatus
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.MediaTrack
import com.adsamcik.streamferry.domain.ServerProfile
import com.adsamcik.streamferry.logging.LogEntry
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.PlaybackAttemptDescriptor
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackQueue
import com.adsamcik.streamferry.playback.RecoveryBudget
import com.adsamcik.streamferry.playback.RecoveryBudgetStatus
import com.adsamcik.streamferry.playback.RecoveryBudgetUsage
import com.adsamcik.streamferry.playback.status
import com.adsamcik.streamferry.ui.theme.ThemeMode

/** Navigation routes (§18 screens). */
enum class Route {
    WELCOME, SERVER_SETUP, LOGIN, GALLERY, MEDIA_DETAIL,
    TARGET_PICKER, PLAYBACK, DOWNLOADS, DIAGNOSTICS, SETTINGS, SERVERS, ABOUT,
}

/** State of the manual server-connection step. */
enum class ConnectionState { IDLE, TESTING, CONNECTED, FAILED }

/** Immutable UI state for the whole app shell (§2 immutable UI state). */
data class AppUiState(
    val route: Route = Route.WELCOME,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,           // actionable, redacted; never a raw stack trace

    // ----- crash reports detected from a previous run (startup export prompt) -----
    val crashAlertCount: Int? = null,           // non-null => show the "crash detected" dialog

    // ----- server setup -----
    val serverUrlInput: String = "",
    val allowHttp: Boolean = false,
    val needsHttpApproval: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val serverName: String? = null,             // safe display string (name + version)

    // ----- auth -----
    /** A live, identity-verified Jellyfin session is installed. */
    val loggedIn: Boolean = false,
    /** A tokenless scope has usable Jellyfin cache/download metadata while live verification is unavailable. */
    val hasCachedJellyfinSession: Boolean = false,
    /** Reachability of the active Jellyfin library; never used as a substitute for authentication. */
    val jellyfinLibraryStatus: JellyfinLibraryStatus = JellyfinLibraryStatus.UNKNOWN,
    val servers: List<ServerProfile> = emptyList(),

    // ----- quick connect (device-code style login) -----
    val quickConnect: QuickConnectUiState? = null,

    // ----- gallery (libraries + drill-down) -----
    /** Active media source id for the gallery switcher (Jellyfin / on-device). */
    val activeSourceId: String = MediaSourceIds.JELLYFIN,
    val libraries: List<MediaItem> = emptyList(),
    /** "Continue Watching" (Jellyfin resume list) shown at the top of the library root; empty otherwise. */
    val continueWatching: List<MediaItem> = emptyList(),
    val folderStack: List<MediaItem> = emptyList(), // breadcrumb of opened folders
    val items: List<MediaItem> = emptyList(),       // children of the current folder
    val galleryLoading: Boolean = false,
    val selectedItem: MediaItem? = null,
    /** Item ids with a server-side watched/progress mutation in flight; controls are disabled to avoid races. */
    val watchStateMutationItemIds: Set<String> = emptySet(),

    // ----- library search -----
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val searching: Boolean = false,

    // ----- targets -----
    val isScanningTargets: Boolean = false,
    val castAvailable: Boolean = true,
    val castTargets: List<DiscoveredTarget> = emptyList(),
    val dlnaTargets: List<DiscoveredTarget> = emptyList(),
    /** Conservative protocol-independent picker rows derived from the raw discovery snapshots above. */
    val physicalTvs: List<PhysicalTv> = emptyList(),
    val selectedPhysicalTv: PhysicalTv? = null,
    /** Stable physical-TV id while a user-initiated start is in flight; null outside that short window. */
    val playbackStartingTargetId: String? = null,
    /** Active protocol endpoint; kept separate from the user's physical-TV selection. */
    val selectedTarget: DiscoveredTarget? = null,
    /** Safe display hint from Smart Resume; it is never used as identity evidence. */
    val previousPhysicalTvName: String? = null,
    val localNetworkPermissionGranted: Boolean = false,

    // ----- background playback -----
    /** Whether the app is exempt from battery optimization (needed for reliable screen-off casting). */
    val backgroundPlaybackUnrestricted: Boolean = true,

    // ----- appearance -----
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    // ----- playback -----
    val playback: PlaybackUiState? = null,
    /**
     * The item actually active on the TV. It deliberately remains separate from [selectedItem], so
     * opening another title while browsing cannot change the now-playing controls or autoplay target.
     */
    val nowPlayingItem: MediaItem? = null,
    /** Session-scoped FIFO playlist. Entries retain their own ids so the same media can be queued twice. */
    val playlist: PlaybackQueue = PlaybackQueue(),

    /** Latest app-wide renderer-confirmed checkpoint, displayed at either gallery root. */
    val smartResume: SmartResumeUiState? = null,

    // ----- offline downloads -----
    val downloads: List<DownloadUiItem> = emptyList(),
    val downloadsBackRoute: Route = Route.GALLERY,
    val selectedDownloadId: String? = null,    // when casting a downloaded copy offline

    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
) {
    /** The folder currently being browsed, or null at the library root. */
    val currentFolder: MediaItem? get() = folderStack.lastOrNull()

    /** True when Jellyfin content can be browsed from a live session or its safe tokenless cache scope. */
    val canBrowseJellyfin: Boolean get() = loggedIn || hasCachedJellyfinSession

    /** Download state for a given item id, or null if not downloaded/downloading. */
    fun downloadFor(itemId: String?): DownloadUiItem? = itemId?.let { id -> downloads.firstOrNull { it.itemId == id } }

    /** How a gallery/detail item can be used at this moment. */
    fun availabilityFor(item: MediaItem): JellyfinItemAvailability = when {
        item.sourceId != MediaSourceIds.JELLYFIN -> JellyfinItemAvailability.AVAILABLE
        downloadFor(item.id)?.completed == true -> JellyfinItemAvailability.DOWNLOADED
        jellyfinLibraryStatus == JellyfinLibraryStatus.UNAVAILABLE -> JellyfinItemAvailability.UNAVAILABLE
        else -> JellyfinItemAvailability.AVAILABLE
    }
}

/** Visual/playback availability for a Jellyfin item; a completed download always wins over an outage. */
enum class JellyfinItemAvailability { AVAILABLE, UNAVAILABLE, DOWNLOADED }

/** UI view of a download (completed or in-progress). No secrets. */
data class DownloadUiItem(
    val itemId: String,
    val title: String,
    val statusText: String,
    val fraction: Float?,        // 0..1 while downloading, null when indeterminate
    val completed: Boolean,
    val failed: Boolean = false,
)

/**
 * Quick Connect flow state. [code] is shown for the user to approve on their Jellyfin server; the
 * secret used to poll is held only in the ViewModel and never surfaced here.
 */
data class QuickConnectUiState(
    val code: String,
    val waiting: Boolean = true,
)

data class PlaybackUiState(
    val targetName: String,
    val protocol: String,
    val mediaTitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionSeconds: Long = 0,
    val durationSeconds: Long? = null,
    val streamMode: String = "",
    val currentBitrateBps: Long = 0,
    val measuredThroughputBps: Long = 0,
    val adaptiveNote: String = "",
    /** Bitrate rungs the user can pin the quality to (empty or ≤1 hides the quality picker). */
    val availableBitratesBps: List<Long> = emptyList(),
    /** True when the user pinned a specific quality (adaptation paused); false = Auto (adaptive). */
    val isManualQuality: Boolean = false,
    /** Video codecs the TV can accept (best-first) for the manual codec picker. */
    val availableVideoCodecs: List<String> = emptyList(),
    /** Manually-chosen transcode codec (e.g. "hevc"), or null for automatic. */
    val preferredVideoCodec: String? = null,
    /** Saved automatic resolution cap, retained while a playback-only override is active. */
    val automaticMaxVideoHeight: Int? = null,
    /** Active server-stream resolution cap, or null when this is a local session. */
    val maxVideoHeight: Int? = null,
    /** True when the current session overrides the saved resolution cap. */
    val isManualMaxVideoHeight: Boolean = false,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
    /** Source media summary (codec · container · resolution) for the "what's playing" card, or null. */
    val sourceFormat: String? = null,
    /** Transcode output summary (codec · resolution · engine), or null for direct play. */
    val outputFormat: String? = null,
    val volume: Float = 1f,
    val volumeSupported: Boolean = false,
    val errorMessage: String? = null,
    val phase: PlaybackPhase = PlaybackPhase.STOPPED,
    val attemptGeneration: Long = 0,
    val attemptHistory: List<PlaybackAttemptDescriptor> = emptyList(),
    val recoveryBudget: RecoveryBudgetStatus = RecoveryBudget().status(RecoveryBudgetUsage()),
    val isTerminal: Boolean = false,
    /** True while the app is auto-reconnecting after an unexpected drop (shows a reconnecting overlay). */
    val reconnecting: Boolean = false,
    /** Selectable audio tracks (empty hides the audio picker). */
    val audioTracks: List<MediaTrack> = emptyList(),
    /** Selectable subtitle tracks (the picker always offers "Off" plus these). */
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** Active audio track index, or null if unknown. */
    val currentAudioIndex: Int? = null,
    /** Active subtitle track index, or null when subtitles are off. */
    val currentSubtitleIndex: Int? = null,
    /** Label for a skippable segment covering the current position (e.g. "Skip intro"), or null. */
    val skipSegmentLabel: String? = null,
)

/** Compact now-playing snapshot for the diagnostics page (no secrets). */
data class NowPlayingDiagnostics(
    val target: String,
    val protocol: String,
    val mode: String,
    val bitrateKbps: Long,
    val throughputKbps: Long,
    val positionLabel: String,
    val buffering: Boolean,
)

data class DiagnosticsUiState(
    val sdkInfo: String = "",
    val localNetworkPermission: String = "unknown",
    val googlePlayServices: String = "unknown",
    /** Whether the app is exempt from battery optimization (affects screen-off casting reliability). */
    val backgroundUnrestricted: Boolean = true,
    val vpnActive: Boolean = false,
    val lanIpRedacted: String = "",
    val proxyAddressRedacted: String = "",
    val redactedLog: List<String> = emptyList(),
    val crashCount: Int = 0,
    val latestCrash: String? = null,
    val tvTracingEnabled: Boolean = false,
    val appVersion: String = "",
    val buildType: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val wifiConnected: Boolean = false,
    val wifiNetworkAvailable: Boolean = false,
    val notificationsGranted: Boolean = false,
    val nowPlaying: NowPlayingDiagnostics? = null,
    val entries: List<LogEntry> = emptyList(),
)
