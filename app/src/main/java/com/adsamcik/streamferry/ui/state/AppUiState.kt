package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.MediaTrack
import com.adsamcik.streamferry.domain.ServerProfile
import com.adsamcik.streamferry.logging.LogEntry

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
    val loggedIn: Boolean = false,
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

    // ----- library search -----
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val searching: Boolean = false,

    // ----- targets -----
    val isScanningTargets: Boolean = false,
    val castAvailable: Boolean = true,
    val castTargets: List<DiscoveredTarget> = emptyList(),
    val dlnaTargets: List<DiscoveredTarget> = emptyList(),
    val selectedTarget: DiscoveredTarget? = null,
    val localNetworkPermissionGranted: Boolean = false,

    // ----- playback -----
    val playback: PlaybackUiState? = null,

    // ----- offline downloads -----
    val downloads: List<DownloadUiItem> = emptyList(),
    val selectedDownloadId: String? = null,    // when casting a downloaded copy offline

    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
) {
    /** The folder currently being browsed, or null at the library root. */
    val currentFolder: MediaItem? get() = folderStack.lastOrNull()

    /** Download state for a given item id, or null if not downloaded/downloading. */
    fun downloadFor(itemId: String?): DownloadUiItem? = itemId?.let { id -> downloads.firstOrNull { it.itemId == id } }
}

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
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
    /** Source media summary (codec · container · resolution) for the "what's playing" card, or null. */
    val sourceFormat: String? = null,
    /** Transcode output summary (codec · resolution · engine), or null for direct play. */
    val outputFormat: String? = null,
    val volume: Float = 1f,
    val errorMessage: String? = null,
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
