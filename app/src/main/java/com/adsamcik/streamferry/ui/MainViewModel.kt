package com.adsamcik.streamferry.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.app.AppContainer
import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import com.adsamcik.streamferry.core.language.LanguagePreferenceResolver
import com.adsamcik.streamferry.core.language.SubtitleMemory
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.resume.SmartResumeDeviceContext
import com.adsamcik.streamferry.core.resume.SmartResumePositionReconciler
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.data.cache.LibraryCache
import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.data.download.DownloadFormat
import com.adsamcik.streamferry.data.download.DownloadIdentity
import com.adsamcik.streamferry.data.download.DownloadOwner
import com.adsamcik.streamferry.data.download.MediaDownloader.DownloadState
import com.adsamcik.streamferry.data.jellyfin.HttpApprovalRequiredException
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.data.jellyfin.JellyfinWatchMutation
import com.adsamcik.streamferry.data.jellyfin.JellyfinWatchMutationKind
import com.adsamcik.streamferry.data.jellyfin.JellyfinWatchMutationStore
import com.adsamcik.streamferry.data.jellyfin.QuickConnectSession
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.JellyfinLibraryScope
import com.adsamcik.streamferry.domain.JellyfinLibraryStatus
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.data.security.SecureStorageException
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.domain.isJellyfinEpisode
import com.adsamcik.streamferry.domain.withJellyfinProgressReset
import com.adsamcik.streamferry.domain.withJellyfinWatchState
import com.adsamcik.streamferry.permissions.AndroidNetworkPermissionManager
import com.adsamcik.streamferry.physical.PhysicalEndpointKey
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.physical.PhysicalTvAggregator
import com.adsamcik.streamferry.physical.PhysicalTvReconnectMatch
import com.adsamcik.streamferry.physical.PhysicalTvReconnectPolicy
import com.adsamcik.streamferry.physical.PhysicalTvResumeMatcher
import com.adsamcik.streamferry.playback.PlaybackStatus
import com.adsamcik.streamferry.playback.PlaybackQueue
import com.adsamcik.streamferry.playback.PlaylistEntry
import com.adsamcik.streamferry.playback.PlaybackFailureCause
import com.adsamcik.streamferry.playback.PlaybackFailureStage
import com.adsamcik.streamferry.playback.PlaybackCompletion
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackPreparationException
import com.adsamcik.streamferry.playback.PlaybackPositionPolicy
import com.adsamcik.streamferry.playback.PlaybackRecoveryContinuation
import com.adsamcik.streamferry.ui.navigation.GalleryBrowseTarget
import com.adsamcik.streamferry.ui.navigation.GalleryLoadRequest
import com.adsamcik.streamferry.ui.navigation.GalleryLoadRequestGate
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.ConnectionState
import com.adsamcik.streamferry.ui.state.DiagnosticsUiState
import com.adsamcik.streamferry.ui.state.DownloadUiItem
import com.adsamcik.streamferry.ui.state.JellyfinItemAvailability
import com.adsamcik.streamferry.ui.state.NowPlayingDiagnostics
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.state.QuickConnectUiState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.state.toUiState
import com.adsamcik.streamferry.ui.theme.ThemeMode
import com.adsamcik.streamferry.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.IOException
import java.util.UUID

/**
 * Single app-shell ViewModel exposing immutable [AppUiState] via [StateFlow]. The UI calls intent
 * methods; the ViewModel orchestrates domain services off the main thread. No business logic, URL
 * construction, token access or socket work happens in Composables (§19).
 */
class MainViewModel(
    private val container: AppContainer,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        AppUiState(
            route = NavigationStatePolicy.restoreRoute(savedStateHandle[STATE_ROUTE]),
            downloadsBackRoute = NavigationStatePolicy.restoreDownloadsOrigin(
                savedStateHandle[STATE_DOWNLOADS_ORIGIN],
            ),
            activeSourceId = NavigationStatePolicy.restoreSource(savedStateHandle[STATE_SOURCE]),
            searchQuery = savedStateHandle[STATE_SEARCH_QUERY] ?: "",
            themeMode = container.appearancePreferences.themeMode,
            backgroundPlaybackUnrestricted = container.permissions.isBatteryOptimizationExempt(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // Declared BEFORE init: the download/status collectors below run synchronously during construction
    // (viewModelScope uses Dispatchers.Main.immediate and a StateFlow emits its current value on
    // collect), and mergeDownloads() reads these — a later declaration would leave them null at that
    // moment (NPE).
    private var downloadEntries: List<DownloadEntry> = emptyList()
    private val downloadTitles = HashMap<DownloadIdentity, String>()
    private val downloadRefreshMutex = Mutex()

    private data class JellyfinWatchMutationKey(
        val serverId: String,
        val userId: String,
        val itemId: String,
    )

    private data class JellyfinWatchStateOverride(
        val operationId: String,
        val transform: (MediaItem) -> MediaItem,
        val clearsResume: Boolean,
    )

    private data class JellyfinWatchStateReconciliation(
        val mutation: JellyfinWatchMutation,
        val session: UserSession,
        val cachePatch: LibraryCache.ItemPatchToken,
    )

    // Prevent conflicting watched/reset requests for one account-scoped item from racing on the server or cache.
    private val activeWatchStateMutations = mutableSetOf<JellyfinWatchMutationKey>()
    // Invalidates an older detail response after a successful server-side watch-state mutation.
    private val jellyfinWatchStateRevisions = mutableMapOf<JellyfinWatchMutationKey, Long>()
    // Applies the latest confirmed action to a result that was already in flight when the action completed.
    private val jellyfinWatchStateOverrides = mutableMapOf<JellyfinWatchMutationKey, JellyfinWatchStateOverride>()
    // Each persistent patch gets a token so a post-confirm remote response can retire it without letting an
    // older in-flight request regress cache/UI state.
    private val jellyfinWatchStateCachePatches = mutableMapOf<String, LibraryCache.ItemPatchToken>()
    private val pendingWatchStateReconciliations = mutableMapOf<String, JellyfinWatchStateReconciliation>()
    private val activeWatchStateReconciliations = mutableSetOf<String>()
    private val watchStateMaterializationMutex = Mutex()
    private var deletingAllData = false
    // Session transitions cancel this child tree before taking AuthRepository's rebind lock, so a retrying
    // manual action cannot make logout or server switching wait behind HTTP timeouts.
    private var watchStateMutationSupervisor: Job = newWatchStateMutationSupervisor()
    private var watchStateMutationScope = newWatchStateMutationScope(watchStateMutationSupervisor)
    // UserSession deliberately excludes the token, so a token refresh for the same account does not clear
    // an in-flight action's spinner or allow a same-item duplicate request.
    private var observedJellyfinSession: UserSession? = null

    private fun newWatchStateMutationSupervisor(): Job =
        SupervisorJob(viewModelScope.coroutineContext[Job])

    private fun newWatchStateMutationScope(supervisor: Job): CoroutineScope =
        CoroutineScope(viewModelScope.coroutineContext + supervisor)
    // One current browse request owns gallery publication. Every folder/source transition supersedes
    // earlier work so a late Jellyfin response cannot replace the list currently on screen.
    private var galleryLoadJob: Job? = null
    private val galleryLoadGate = GalleryLoadRequestGate()

    // ----- auto-reconnect after an unexpected renderer disconnect -----
    private var reconnectJob: Job? = null
    private var reconnectGeneration = 0L
    // The in-flight device scan, so a play tap can cancel it (its progress spinner is an infinite Compose
    // animation that would otherwise keep posting main-thread frames during the FGS start window).
    private var scanJob: Job? = null
    @Volatile private var reconnecting = false
    @Volatile private var playbackStarting = false
    // True while an end-of-media or explicit queue skip is handing off to the next item. Guards the
    // status->null "ended" branch so the mini-player and playback screen do not flicker away mid-handoff.
    @Volatile private var autoAdvancing = false
    /** Monotonic session-local id: duplicate media entries remain independently removable/repeatable. */
    private var playlistEntrySequence = 0L
    private var lastPlaybackPositionSeconds: Long = 0L
    // What to re-play on reconnect (Jellyfin online OR an on-device / downloaded local file). Set after a
    // successful play()/playLocal(), cleared on stop / a new play.
    private var reconnectContext: ReconnectContext? = null
    private var pendingResumeDeviceContext: SmartResumeDeviceContext? = null
    private var lastRecordedPhysicalAttemptGeneration = -1L
    /**
     * The exact verified account that owns the live online renderer session. This is deliberately
     * separate from the mutable singleton Jellyfin client: account switching must tear this session
     * down before the client is rebound, and delayed completion/autoplay work must fail closed.
     */
    private var onlinePlaybackOwner: UserSession? = null

    private sealed interface ReconnectContext {
        val physicalTv: PhysicalTv
        val endpoint: DiscoveredTarget
        data class Online(
            val item: MediaItem,
            override val physicalTv: PhysicalTv,
            override val endpoint: DiscoveredTarget,
            val owner: UserSession,
        ) : ReconnectContext
        data class Local(
            val filePath: String,
            val contentType: String,
            val title: String,
            val runtimeSeconds: Long?,
            override val physicalTv: PhysicalTv,
            override val endpoint: DiscoveredTarget,
            val downloadId: String?,
            val smartResumeSeed: SmartResumeSeed?,
        ) : ReconnectContext
    }

    // The current session's resume key (a content:// URI, or an account-scoped downloaded-copy key) + duration, used to remember
    // "where you left off" for local files (Jellyfin uses its own server-side resume). Cleared on stop.
    @Volatile private var currentResumeKey: String? = null
    @Volatile private var currentDurationSeconds: Long? = null
    private var lastResumeSaveMs: Long = 0L
    // Set only by the root Smart Resume card; consumed by the next selected-target play.
    private var smartResumeOverrideSeconds: Long? = null

    init {
        viewModelScope.launch {
            state.collect { current ->
                savedStateHandle[STATE_ROUTE] = NavigationStatePolicy.persistRoute(current.route)
                savedStateHandle[STATE_DOWNLOADS_ORIGIN] = current.downloadsBackRoute.name
                savedStateHandle[STATE_SOURCE] = current.activeSourceId
                savedStateHandle[STATE_SEARCH_QUERY] = current.searchQuery
            }
        }
        onLocalNetworkPermissionResult(container.permissions.hasLocalNetworkAccess())
        // Runtime permission may be revoked outside this activity. Cancel work and remove cached targets
        // before a stale selection can trigger a reconnect or proxy start.
        viewModelScope.launch {
            container.permissions.localNetworkAccess.drop(1).collect { granted ->
                if (granted) onLocalNetworkPermissionResult(true) else onLocalNetworkPermissionDenied()
            }
        }
        // Live playback status from the engine (position, buffering, adaptive bitrate, throughput).
        viewModelScope.launch {
            container.playbackEngine.status.collect { status ->
                if (status != null) {
                    lastPlaybackPositionSeconds = status.positionSeconds
                    // The engine may have probed a missing SAF/MediaStore duration. Retain that authoritative
                    // timeline so near-end local checkpoints are removed instead of resurfacing as resume.
                    if (currentResumeKey != null && status.durationSeconds?.let { it > 0L } == true) {
                        currentDurationSeconds = status.durationSeconds
                    }
                    saveResumeThrottled(status.positionSeconds)
                    recordSuccessfulPhysicalEndpoint(status)
                }
                // An unexpected renderer drop -> kick off auto-reconnect + resume (idempotent).
                if (status?.connectionLost == true) {
                    startReconnect()
                } else if (status?.isTerminal == true) {
                    startTerminalProtocolFallback(status)
                }
                _state.update { cur ->
                    // A hand-off can briefly publish null while the old renderer tears down. Keep the
                    // now-playing presentation mounted until the replacement has begun.
                    val retainingPlayback = playbackStarting || reconnecting || autoAdvancing
                    val ended = status == null && cur.route == Route.PLAYBACK && !retainingPlayback
                    val ui = status?.toUi()?.copy(reconnecting = reconnecting)
                        ?: cur.playback?.takeIf { retainingPlayback }
                            ?.copy(reconnecting = reconnecting)
                    cur.copy(
                        playback = ui,
                        nowPlayingItem = if (ui == null && !retainingPlayback) null else cur.nowPlayingItem,
                        route = if (ended) Route.GALLERY else cur.route,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.authRepository.currentUser.collect { user ->
                // All normal rebind paths stop online playback first. Retain a fail-closed backstop for
                // a future/background repository mutation: an A-owned renderer must never keep using a
                // singleton client after B becomes current.
                val orphanedOnlinePlayback = onlinePlaybackOwner?.takeIf { it != user }
                if (orphanedOnlinePlayback != null) {
                    onlinePlaybackOwner = null
                    if (reconnectContext is ReconnectContext.Online) {
                        cancelReconnect()
                        reconnectContext = null
                        autoAdvancing = false
                    }
                    container.logger.event("playback", "Stopping playback after its Jellyfin account changed")
                    viewModelScope.launch {
                        runCatching { container.playbackEngine.stop() }
                            .onFailure { error -> container.logger.w("playback", "Couldn't stop stale Jellyfin playback", error) }
                    }
                }
                val sessionChanged = observedJellyfinSession != user
                observedJellyfinSession = user
                // A prior account's overlays and revisions are never valid for a newly materialized scope.
                if (sessionChanged) {
                    activeWatchStateMutations.clear()
                    jellyfinWatchStateRevisions.clear()
                    jellyfinWatchStateOverrides.clear()
                    jellyfinWatchStateCachePatches.clear()
                    pendingWatchStateReconciliations.clear()
                    activeWatchStateReconciliations.clear()
                }
                _state.update { current ->
                    current.copy(
                        loggedIn = user != null,
                        smartResume = container.smartResumeStore.current
                            ?.takeUnless(::isSmartResumeBlockedByWatchMutation)
                            ?.toUiState(user),
                        // A mutation in a previous account must never leave this account's controls disabled,
                        // while a token refresh for the same account keeps its current operation visible.
                        watchStateMutationItemIds = if (sessionChanged) emptySet() else current.watchStateMutationItemIds,
                    )
                }
                refreshDownloadEntries()
                // Pending requests are owner-scoped. Resuming only after this live session is installed
                // prevents a sticky service or connectivity callback from using this account's client
                // credentials to request another account's item id.
                user?.let { activeUser ->
                    schedulePendingJellyfinWatchStateReplay(activeUser)
                    val owner = DownloadOwner(serverId = activeUser.serverId, userId = activeUser.userId)
                    if (container.authRepository.currentUser.value == activeUser &&
                        runCatching { container.downloader.hasPendingPersisted(owner) }.getOrDefault(false)
                    ) {
                        runCatching { container.startDownloadService() }
                    }
                }
            }
        }
        viewModelScope.launch {
            container.authRepository.cachedSession.collect { cached ->
                _state.update { it.copy(hasCachedJellyfinSession = cached != null) }
                if (cached != null) materializePendingJellyfinWatchStateMutations(cached)
                refreshDownloadEntries()
            }
        }
        viewModelScope.launch {
            container.jellyfinConnectionMonitor.status.collect { status ->
                _state.update { it.copy(jellyfinLibraryStatus = status) }
                // ONLINE is emitted only after a verified authenticated Jellyfin request succeeds. It is a
                // safe recovery boundary for retrying durable actions that previously lost connectivity.
                if (!deletingAllData && status == JellyfinLibraryStatus.ONLINE) {
                    container.authRepository.currentUser.value?.let(::schedulePendingJellyfinWatchStateReplay)
                    pendingWatchStateReconciliations.values.toList()
                        .forEach(::scheduleJellyfinWatchStateReconciliation)
                }
            }
        }
        viewModelScope.launch {
            container.smartResumeStore.record.collect { record ->
                _state.update {
                    it.copy(
                        smartResume = record
                            ?.takeUnless(::isSmartResumeBlockedByWatchMutation)
                            ?.toUiState(container.authRepository.currentUser.value),
                    )
                }
            }
        }
        // Autoplay the next episode when one finishes (Netflix-style). end-of-media fires only on a genuine
        // finish (not a user stop); if enabled and the finished item is a series episode with a next one,
        // play it on the same TV.
        viewModelScope.launch {
            container.playbackEngine.endOfMedia.collect { completion -> onEndOfMedia(completion) }
        }
        // Rehydrate a non-secret scope first, so a previous Jellyfin gallery and completed downloads are
        // useful immediately when the server is down. Live restoration below independently verifies the
        // server identity before installing the stored token.
        viewModelScope.launch {
            val cached = runCatching { container.authRepository.restoreCachedSession() }.getOrNull()
            if (cached != null) {
                _state.update { it.copy(hasCachedJellyfinSession = true) }
                materializePendingJellyfinWatchStateMutations(cached)
            }
            val initialGallery = _state.value.route == Route.GALLERY
            if (initialGallery && (cached != null || _state.value.activeSourceId == MediaSourceIds.LOCAL)) {
                val restoredQuery = _state.value.searchQuery
                _state.update { it.copy(galleryLoading = true) }
                launchGalleryLoad()
                if (restoredQuery.isNotBlank()) onSearchQueryChanged(restoredQuery)
            }

            val restored = container.authRepository.ensureOnlineSession().getOrNull()
            if (restored != null) {
                container.jellyfinConnectionMonitor.markOnline()
                _state.update { it.copy(loggedIn = true, connectionState = ConnectionState.CONNECTED) }
                if (_state.value.route == Route.GALLERY && _state.value.activeSourceId == MediaSourceIds.JELLYFIN) {
                    val restoredQuery = _state.value.searchQuery
                    _state.update { it.copy(galleryLoading = true) }
                    launchGalleryLoad()
                    if (restoredQuery.isNotBlank()) onSearchQueryChanged(restoredQuery)
                }
            } else if (cached != null) {
                container.jellyfinConnectionMonitor.markUnavailable()
            }
        }
        // Offline downloads: completed set (persistent) + live progress.
        viewModelScope.launch { refreshDownloadEntries() }
        viewModelScope.launch {
            container.downloader.states.collect { states ->
                if (states.values.any { it is DownloadState.Completed }) refreshDownloadEntries()
                _state.update { it.copy(downloads = mergeDownloads(states)) }
            }
        }

        // If a previous run crashed ON THIS BUILD, prompt to export the report on launch (works before
        // login too). Only latest-build crashes are counted/shared — older-build reports (from before an
        // update) aren't relevant to the current build.
        viewModelScope.launch {
            val crashes = withContext(Dispatchers.IO) { runCatching { container.crashReporter.countForCurrentBuild() }.getOrDefault(0) }
            if (crashes > 0) _state.update { it.copy(crashAlertCount = crashes) }
        }
    }

    private suspend fun refreshDownloadEntries() = downloadRefreshMutex.withLock {
        val owner = activeDownloadOwner()
        val entries = owner?.let { currentOwner ->
            runCatching { container.downloadStore.list(currentOwner) }.getOrDefault(emptyList())
        }.orEmpty()
        // Do not publish entries for a profile that switched while the disk read was in flight.
        if (owner != activeDownloadOwner()) return@withLock
        downloadEntries = entries
        downloadTitles.clear()
        entries.forEach { entry ->
            owner?.let { downloadTitles[DownloadIdentity(it, entry.itemId)] = entry.title }
        }
        _state.update { it.copy(downloads = mergeDownloads(container.downloader.states.value)) }
    }

    private fun mergeDownloads(states: Map<DownloadIdentity, DownloadState>): List<DownloadUiItem> {
        val owner = activeDownloadOwner() ?: return emptyList()
        val byId = LinkedHashMap<String, DownloadUiItem>()
        downloadEntries.forEach { entry ->
            byId[entry.itemId] = DownloadUiItem(entry.itemId, entry.title, "Downloaded", 1f, completed = true)
        }
        states.filterKeys { it.owner == owner }.forEach { (identity, status) ->
            val itemId = identity.itemId
            val title = downloadTitles[identity] ?: container.downloader.titleFor(itemId, owner) ?: itemId
            val ui = when (status) {
                is DownloadState.Queued -> DownloadUiItem(itemId, title, "Queued…", null, completed = false)
                is DownloadState.Running -> DownloadUiItem(itemId, title, runningText(status), status.fraction, completed = false)
                is DownloadState.Completed -> byId[itemId] ?: DownloadUiItem(itemId, title, "Downloaded", 1f, completed = true)
                is DownloadState.Failed -> DownloadUiItem(itemId, title, status.reason, null, completed = false, failed = true)
            }
            if (status !is DownloadState.Completed || byId[itemId] == null) byId[itemId] = ui
        }
        return byId.values.sortedBy { it.title.lowercase() }
    }

    private fun runningText(r: DownloadState.Running): String {
        val mb = r.downloadedBytes / (1024 * 1024)
        val pct = r.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
        return "Downloading$pct (${mb} MiB)"
    }

    fun navigate(route: Route) {
        val snapshot = _state.value
        val safeRoute = when {
            route == Route.MEDIA_DETAIL && snapshot.selectedItem == null -> Route.GALLERY
            route == Route.TARGET_PICKER && snapshot.selectedItem == null && snapshot.selectedDownloadId == null -> Route.GALLERY
            route == Route.PLAYBACK && snapshot.playback == null -> Route.GALLERY
            else -> route
        }
        val leavingTargetPicker = snapshot.route == Route.TARGET_PICKER && safeRoute != Route.TARGET_PICKER
        _state.update { it.copy(route = safeRoute, errorMessage = null) }
        if (leavingTargetPicker) stopTargetDiscovery()
        if (safeRoute == Route.GALLERY && _state.value.libraries.isEmpty() && !_state.value.galleryLoading) {
            val restoredQuery = _state.value.searchQuery
            _state.update { it.copy(galleryLoading = true) }
            viewModelScope.launch {
                launchGalleryLoad()
                if (restoredQuery.isNotBlank()) onSearchQueryChanged(restoredQuery)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun onWelcomeContinue() {
        if (_state.value.canBrowseJellyfin) openLibraries() else navigate(Route.SERVER_SETUP)
    }

    /** Open the multi-server picker (kept even when offline). */
    fun openServers() = viewModelScope.launch {
        _state.update { it.copy(route = Route.SERVERS, servers = container.authRepository.servers()) }
    }

    /** Switch the active Jellyfin server; resume its library, or go to login if it needs re-auth / can't verify. */
    fun switchServer(serverId: String) {
        // A device code belongs to one Jellyfin server. Clear it before the repository changes origins so
        // the UI cannot keep polling or display a code from the previous server while the switch queues.
        cancelQuickConnect()
        viewModelScope.launch {
            try {
                stopOnlinePlaybackForJellyfinSessionTransition("switching servers")
                pauseDownloadsForAuthTransition()
                val session = container.authRepository.switchServer(serverId)
                val cached = container.authRepository.cachedSession.value
                when {
                    session != null -> {
                        container.jellyfinConnectionMonitor.markOnline()
                        openLibraries()
                    }
                    cached != null -> {
                        container.jellyfinConnectionMonitor.markUnavailable()
                        _state.update { it.copy(hasCachedJellyfinSession = true, loggedIn = false) }
                        openLibraries()
                    }
                    else -> _state.update {
                        it.copy(route = Route.SERVER_SETUP, loggedIn = false, errorMessage = "Sign in to that server to continue.")
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: SecureStorageException) {
                container.logger.e("security", "Couldn't persist active server", e)
                _state.update {
                    it.copy(
                        loggedIn = container.authRepository.currentUser.value != null,
                        hasCachedJellyfinSession = container.authRepository.cachedSession.value != null,
                        errorMessage = SECURE_STORAGE_ERROR,
                    )
                }
            }
        }
    }

    fun forgetServer(serverId: String) {
        cancelQuickConnect()
        viewModelScope.launch {
            try {
                if (activeJellyfinSession()?.serverId == serverId) {
                    stopOnlinePlaybackForJellyfinSessionTransition("removing the active server")
                    pauseDownloadsForAuthTransition()
                }
                container.authRepository.deleteServerProfile(serverId)
                withContext(Dispatchers.IO) { container.jellyfinWatchMutationStore.removeServer(serverId) }
                _state.update { it.copy(servers = container.authRepository.servers()) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: SecureStorageException) {
                container.logger.e("security", "Couldn't remove saved server credentials", e)
                _state.update {
                    it.copy(
                        loggedIn = container.authRepository.currentUser.value != null,
                        hasCachedJellyfinSession = container.authRepository.cachedSession.value != null,
                        errorMessage = SECURE_STORAGE_ERROR,
                    )
                }
            }
        }
    }

    /** Skip Jellyfin entirely and browse on-device videos. */
    fun useLocalOnly() {
        _state.update { it.copy(activeSourceId = MediaSourceIds.LOCAL) }
        openLibraries()
    }

    // ----- server setup -----

    fun onServerUrlChanged(url: String) = _state.update {
        it.copy(serverUrlInput = url, needsHttpApproval = false, connectionState = ConnectionState.IDLE, errorMessage = null)
    }

    fun onAllowHttpChanged(allow: Boolean) = _state.update { it.copy(allowHttp = allow) }

    fun testConnectionAndContinue() {
        // Submitting a new address supersedes any displayed code before the repository reconfigures the client.
        cancelQuickConnect()
        viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(connectionState = ConnectionState.TESTING, isBusy = true, errorMessage = null) }
            stopOnlinePlaybackForJellyfinSessionTransition("configuring a server")
            pauseDownloadsForAuthTransition()
            container.authRepository.setServer(s.serverUrlInput, s.allowHttp).fold(
                onSuccess = { profile ->
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.CONNECTED,
                            serverName = profile.name,
                            isBusy = false,
                            needsHttpApproval = false,
                            errorMessage = null,
                            route = Route.LOGIN,
                        )
                    }
                },
                onFailure = { e ->
                    if (e is HttpApprovalRequiredException) {
                        _state.update {
                            it.copy(connectionState = ConnectionState.IDLE, needsHttpApproval = true, isBusy = false, errorMessage = null)
                        }
                    } else {
                        if (e is SecureStorageException) {
                            container.logger.e("security", "Couldn't persist Jellyfin server", e)
                        }
                        _state.update {
                            it.copy(
                                connectionState = ConnectionState.FAILED,
                                isBusy = false,
                                errorMessage = if (e is SecureStorageException) SECURE_STORAGE_ERROR
                                else e.message ?: "Couldn't connect to that server.",
                            )
                        }
                    }
                },
            )
        }
    }

    // ----- auth -----

    fun login(username: String, password: String) = viewModelScope.launch {
        stopOnlinePlaybackForJellyfinSessionTransition("signing in")
        pauseDownloadsForAuthTransition()
        _state.update { it.copy(isBusy = true, errorMessage = null) }
        container.authRepository.login(username, password).fold(
            onSuccess = {
                container.jellyfinConnectionMonitor.markOnline()
                _state.update { it.copy(isBusy = false, loggedIn = true) }
                openLibraries()
            },
            onFailure = { error ->
                if (error is SecureStorageException) {
                    container.logger.e("security", "Couldn't persist Jellyfin login", error)
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = if (error is SecureStorageException) SECURE_STORAGE_ERROR
                        else "Login failed. Check your username and password.",
                    )
                }
            },
        )
    }

    // ----- Quick Connect (device-code style login) -----

    private var quickConnectJob: Job? = null

    /**
     * Start a Quick Connect handshake: show the code, then poll until the user approves it on their
     * Jellyfin server (or cancels). The opaque session stays here; its secret is never surfaced to UI state.
     */
    fun startQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null) }
            val enabled = try {
                container.authRepository.quickConnectEnabled()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                false
            }
            if (!enabled) {
                _state.update {
                    it.copy(isBusy = false, errorMessage = "Quick Connect isn't enabled on this server.")
                }
                return@launch
            }
            container.authRepository.startQuickConnect().fold(
                onSuccess = { handshake ->
                    _state.update {
                        it.copy(isBusy = false, quickConnect = QuickConnectUiState(code = handshake.code))
                    }
                    pollQuickConnect(handshake)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(isBusy = false, errorMessage = "Couldn't start Quick Connect. Please try again.")
                    }
                },
            )
        }
    }

    private suspend fun pollQuickConnect(session: QuickConnectSession) {
        while (currentCoroutineContext().isActive && _state.value.quickConnect != null) {
            delay(QUICK_CONNECT_POLL_MS)
            val poll = container.authRepository.pollQuickConnect(session)
            poll.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                // The secret expired or was revoked server-side: stop and let the user retry.
                _state.update {
                    it.copy(quickConnect = null, errorMessage = "Quick Connect code expired. Please try again.")
                }
                return
            }
            if (poll.getOrDefault(false) != true) continue
            stopOnlinePlaybackForJellyfinSessionTransition("completing Quick Connect")
            pauseDownloadsForAuthTransition()
            container.authRepository.completeQuickConnect(session).fold(
                onSuccess = {
                    container.jellyfinConnectionMonitor.markOnline()
                    _state.update { it.copy(quickConnect = null, loggedIn = true, errorMessage = null) }
                    openLibraries()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    if (e is SecureStorageException) {
                        container.logger.e("security", "Couldn't persist Quick Connect login", e)
                    }
                    _state.update {
                        it.copy(
                            quickConnect = null,
                            errorMessage = if (e is SecureStorageException) SECURE_STORAGE_ERROR
                            else "Quick Connect sign-in failed. Please try again.",
                        )
                    }
                },
            )
            return
        }
    }

    /** Cancel an in-progress Quick Connect handshake. */
    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        _state.update { it.copy(quickConnect = null, isBusy = false) }
    }

    fun logout() {
        cancelQuickConnect()
        viewModelScope.launch {
            try {
                stopOnlinePlaybackForJellyfinSessionTransition("signing out")
                // Signing out has always stopped local playback too; retain that behavior after the online
                // teardown above (the second stop is idempotent when the active session was online).
                runCatching { container.playbackEngine.stop() }
                pauseDownloadsForAuthTransition()
                container.authRepository.logout()
                container.jellyfinConnectionMonitor.reset()
                _state.value = AppUiState()
            } catch (c: CancellationException) {
                throw c
            } catch (e: SecureStorageException) {
                container.logger.e("security", "Couldn't remove saved login", e)
                _state.update { it.copy(isBusy = false, loggedIn = false, errorMessage = SECURE_STORAGE_ERROR) }
            }
        }
    }

    /**
     * Stop the account-owned online session before a Jellyfin client rebind. [PlaybackEngine.stop]
     * waits for its coordinator to finish (or bound) reportStopped/transcode cleanup while the old
     * credentials are still installed; only then may the singleton client move to another account.
     */
    private suspend fun stopOnlinePlaybackForJellyfinSessionTransition(reason: String) {
        // Cancel first: runWithActiveSession intentionally holds the client binding mutex through the
        // request, and cancellation aborts its OkHttp call/retry delay so this transition can proceed.
        cancelWatchStateMutationsForSessionTransition()
        val hadOnlinePlayback = onlinePlaybackOwner != null || reconnectContext is ReconnectContext.Online
        if (!hadOnlinePlayback) return

        onlinePlaybackOwner = null
        cancelReconnect()
        reconnectContext = null
        pendingResumeDeviceContext = null
        autoAdvancing = false
        playbackStarting = false
        runCatching { container.playbackEngine.stop() }
            .onFailure { error -> container.logger.w("playback", "Couldn't stop online playback before $reason", error) }
        _state.update { current ->
            if (current.nowPlayingItem?.sourceId != MediaSourceIds.JELLYFIN) current else current.copy(
                route = if (current.route == Route.PLAYBACK) Route.GALLERY else current.route,
                playback = null,
                nowPlayingItem = null,
                playbackStartingTargetId = null,
            )
        }
        container.logger.event("playback", "Stopped online playback before $reason")
    }

    /** Stop all manual per-item mutations before a client rebind can wait behind their network retries. */
    private suspend fun cancelWatchStateMutationsForSessionTransition() {
        val previous = watchStateMutationSupervisor
        watchStateMutationSupervisor = newWatchStateMutationSupervisor()
        watchStateMutationScope = newWatchStateMutationScope(watchStateMutationSupervisor)
        previous.cancelAndJoin()
        activeWatchStateMutations.clear()
        jellyfinWatchStateCachePatches.clear()
        pendingWatchStateReconciliations.clear()
        activeWatchStateReconciliations.clear()
        _state.update { current -> current.copy(watchStateMutationItemIds = emptySet()) }
    }

    private fun isActiveOnlinePlaybackOwner(owner: UserSession?): Boolean =
        owner != null && owner == onlinePlaybackOwner && container.authRepository.currentUser.value == owner

    private fun requireActiveOnlinePlaybackOwner(owner: UserSession) {
        if (!isActiveOnlinePlaybackOwner(owner)) {
            throw CancellationException("The Jellyfin playback account changed.")
        }
    }

    /** Keep queued bytes intact while the singleton Jellyfin client changes account/origin. */
    private suspend fun pauseDownloadsForAuthTransition() {
        runCatching { container.downloader.pauseAllAndJoin() }
            .onFailure { container.logger.w("download", "Could not pause downloads before changing Jellyfin session", it) }
    }

    // ----- gallery -----

    fun openLibraries() = viewModelScope.launch {
        _state.update {
            it.copy(
                route = Route.GALLERY, galleryLoading = true, folderStack = emptyList(), items = emptyList(),
                errorMessage = null, searchQuery = "", searchResults = emptyList(), searching = false,
            )
        }
        launchGalleryLoad()
    }

    /** Active browsable source (Jellyfin / on-device), per [AppUiState.activeSourceId]. */
    private fun activeSource(): MediaSource = sourceFor(_state.value.activeSourceId)

    private fun sourceFor(sourceId: String): MediaSource =
        container.mediaSources.firstOrNull { it.id == sourceId } ?: container.jellyfinMediaSource

    private fun AppUiState.galleryTarget(): GalleryBrowseTarget =
        GalleryBrowseTarget(activeSourceId, currentFolder?.id)

    private fun isCurrentGalleryLoad(request: GalleryLoadRequest): Boolean =
        galleryLoadGate.isCurrent(request, _state.value.galleryTarget())

    private inline fun updateCurrentGalleryLoad(
        request: GalleryLoadRequest,
        transform: (AppUiState) -> AppUiState,
    ) {
        _state.update { current ->
            if (galleryLoadGate.isCurrent(request, current.galleryTarget())) transform(current) else current
        }
    }

    private fun cancelGalleryLoad() {
        galleryLoadJob?.cancel()
        galleryLoadJob = null
        galleryLoadGate.invalidate()
    }

    private fun launchGalleryLoad(target: GalleryBrowseTarget = _state.value.galleryTarget()) {
        // A delayed refresh may resume after navigation; it must not start a request for a stale folder.
        if (_state.value.galleryTarget() != target) return
        galleryLoadJob?.cancel()
        val request = galleryLoadGate.begin(target)
        galleryLoadJob = viewModelScope.launch {
            if (target.folderId == null) loadRoots(request) else loadChildren(request)
        }
    }

    /** Sources for the gallery switcher, as (id, displayName) in display order. */
    val sources: List<Pair<String, String>> get() = container.mediaSources.map { it.id to it.displayName }

    /** True when the on-device source has any granted access (a folder, files, or the media permission). */
    fun localHasAccess(): Boolean = container.localMediaSource.hasAnyAccess()

    /** Whether the elective media-library permission is currently granted. */
    fun mediaPermissionGranted(): Boolean = container.permissions.hasReadMediaVideo()

    /** Whether either full-library or Android 14 selected-video access is currently granted. */
    fun mediaAccessGranted(): Boolean = container.permissions.hasAnyMediaVideoAccess()

    /** Runtime permissions that let Android return either full-library or selected-video access. */
    val readMediaVideoPermissions: Array<String>
        get() = AndroidNetworkPermissionManager.MEDIA_VIDEO_PERMISSIONS.copyOf()

    /** Re-reads the system setting after returning from the battery-optimization prompt. */
    fun refreshBackgroundPlaybackStatus() {
        _state.update {
            it.copy(backgroundPlaybackUnrestricted = container.permissions.isBatteryOptimizationExempt())
        }
    }

    /** Intent that asks the system to allow unrestricted background playback (battery-opt exemption). */
    fun batteryOptimizationRequestIntent() = container.permissions.batteryOptimizationRequestIntent()

    /** Switch the gallery to another source, resetting the browse stack and loading its roots. */
    fun selectSource(sourceId: String) {
        if (_state.value.activeSourceId == sourceId) return
        searchJob?.cancel()
        _state.update {
            it.copy(
                activeSourceId = sourceId, libraries = emptyList(), folderStack = emptyList(), items = emptyList(),
                searchQuery = "", searchResults = emptyList(), searching = false, errorMessage = null, galleryLoading = true,
            )
        }
        launchGalleryLoad()
    }

    /** Persist a SAF folder grant for the on-device source and refresh if it is showing. */
    fun onLocalFolderPicked(uri: Uri?) {
        if (uri == null) return
        container.localMediaSource.addFolder(uri)
        reloadIfLocalActive()
    }

    /** Persist SAF file grants for the on-device source and refresh if it is showing. */
    fun onLocalFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        container.localMediaSource.addFiles(uris)
        reloadIfLocalActive()
    }

    /** The user granted (or denied) the elective media-library permission; refresh on grant. */
    fun onMediaPermissionResult(granted: Boolean) {
        if (granted || container.permissions.hasAnyMediaVideoAccess()) reloadIfLocalActive()
    }

    private fun reloadIfLocalActive() {
        if (_state.value.activeSourceId != MediaSourceIds.LOCAL) return
        _state.update { it.copy(galleryLoading = true, folderStack = emptyList(), items = emptyList()) }
        launchGalleryLoad()
    }

    private suspend fun loadRoots(request: GalleryLoadRequest) {
        if (!isCurrentGalleryLoad(request)) return
        val target = request.target
        // Jellyfin can be browsed through an authenticated live session OR an isolated cached-session
        // scope. The latter never permits the repository to make a token-bearing remote request.
        if (target.sourceId == MediaSourceIds.JELLYFIN && !_state.value.canBrowseJellyfin) {
            updateCurrentGalleryLoad(request) {
                it.copy(libraries = emptyList(), continueWatching = emptyList(), galleryLoading = false)
            }
            return
        }
        val result = sourceFor(target.sourceId).roots()
        if (!isCurrentGalleryLoad(request)) return
        result.fold(
            onSuccess = { libraries ->
                val updatedLibraries = withConfirmedJellyfinWatchState(target.sourceId, libraries)
                updateCurrentGalleryLoad(request) { it.copy(libraries = updatedLibraries, galleryLoading = false) }
                // Continue Watching belongs to the source rather than the open folder. It is safe to refresh
                // only while this same source remains selected; a source switch drops the late result.
                loadContinueWatching(target.sourceId)
            },
            onFailure = { error ->
                if (!isCurrentGalleryLoad(request)) return@fold
                if (isSessionExpired(error)) handleSessionExpired()
                // Keep a populated snapshot visible on a manual refresh failure. A first-load failure still
                // naturally presents the empty/error state, but scrolling is never reset by clearing data.
                else updateCurrentGalleryLoad(request) {
                    it.copy(galleryLoading = false, errorMessage = libraryErrorMessage(error))
                }
            },
        )
    }

    /**
     * Load the "Continue Watching" row. The source id is captured before the request so a late result from
     * one Jellyfin account/source cannot replace the row after the user switched elsewhere.
     */
    private suspend fun loadContinueWatching(sourceId: String = _state.value.activeSourceId) {
        val items = withConfirmedJellyfinContinueWatchingState(
            sourceId,
            sourceFor(sourceId).continueWatching().getOrDefault(emptyList()),
        )
        _state.update { current ->
            if (current.activeSourceId == sourceId) current.copy(continueWatching = items) else current
        }
    }

    fun onItemClicked(item: MediaItem) {
        if (_state.value.searchQuery.isNotBlank()) clearSearch()
        if (item.isFolder) openFolder(item) else openDetail(item)
    }

    private fun openFolder(folder: MediaItem) {
        _state.update { it.copy(folderStack = it.folderStack + folder, galleryLoading = true, items = emptyList(), errorMessage = null) }
        launchGalleryLoad()
    }

    /** Go up one level in the gallery; returns to the welcome/library root from the top. */
    fun popFolder() {
        val stack = _state.value.folderStack
        if (stack.isEmpty()) return
        val newStack = stack.dropLast(1)
        _state.update { it.copy(folderStack = newStack, items = emptyList(), galleryLoading = newStack.isNotEmpty()) }
        if (newStack.isEmpty()) cancelGalleryLoad() else launchGalleryLoad()
    }

    private suspend fun loadChildren(request: GalleryLoadRequest) {
        if (!isCurrentGalleryLoad(request)) return
        val target = request.target
        val parentId = target.folderId ?: return
        val result = sourceFor(target.sourceId).children(parentId)
        if (!isCurrentGalleryLoad(request)) return
        result.fold(
            onSuccess = { children ->
                val updatedChildren = withConfirmedJellyfinWatchState(target.sourceId, children)
                updateCurrentGalleryLoad(request) { it.copy(items = updatedChildren, galleryLoading = false) }
            },
            onFailure = { error ->
                if (!isCurrentGalleryLoad(request)) return@fold
                if (isSessionExpired(error)) handleSessionExpired()
                // Children are intentionally left intact during a refresh failure; navigation cleared them
                // before a new folder load, so no prior folder can be shown under a new breadcrumb.
                else updateCurrentGalleryLoad(request) {
                    it.copy(galleryLoading = false, errorMessage = libraryErrorMessage(error))
                }
            },
        )
    }
    /**
     * Detect a 401 anywhere in the cause chain: the server revoked/expired our token, so the stored
     * session is no longer usable.
     */
    private fun isSessionExpired(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is JellyfinHttpException && it.isUnauthorized }

    /**
     * Pick the most informative message from a failure chain: a Jellyfin HTTP error (which now carries
     * the parsed server reason, e.g. "HTTP 500 (Library scan is already running)") is preferred over a
     * generic wrapper so the user sees *why* the server rejected the request, not just the outer label.
     */
    private fun bestFailureReason(t: Throwable): String {
        val http = generateSequence(t) { it.cause }.filterIsInstance<JellyfinHttpException>().firstOrNull()
        return http?.message ?: t.message ?: t.javaClass.simpleName
    }

    /**
     * Library/search error text. When the server itself returned an error (not a transport failure),
     * append the parsed, already-redacted reason so a 500/503 explains itself instead of the generic
     * "check the connection" line.
     */
    private fun libraryErrorMessage(t: Throwable): String {
        val http = generateSequence(t) { it.cause }.filterIsInstance<JellyfinHttpException>().firstOrNull()
            ?: return LIBRARY_ERROR
        val detail = http.serverReason?.takeIf { it.isNotBlank() } ?: "server returned HTTP ${http.code}"
        return "$LIBRARY_ERROR ($detail)"
    }

    /** Clear the dead session and route back to login so the user can re-authenticate. */
    private fun handleSessionExpired() {
        viewModelScope.launch {
            stopOnlinePlaybackForJellyfinSessionTransition("handling an expired session")
            pauseDownloadsForAuthTransition()
            runCatching { container.authRepository.logout() }
        }
        _state.update {
            it.copy(
                route = Route.LOGIN,
                loggedIn = false,
                isBusy = false,
                galleryLoading = false,
                errorMessage = "Your session expired. Please sign in again.",
            )
        }
    }

    private fun openDetail(item: MediaItem) {
        val shouldPreferOfflineCopy = _state.value.availabilityFor(item) == JellyfinItemAvailability.DOWNLOADED &&
            _state.value.jellyfinLibraryStatus == com.adsamcik.streamferry.domain.JellyfinLibraryStatus.UNAVAILABLE
        val detailSession = container.authRepository.currentUser.value
        val detailMutationKey = detailSession
            ?.takeIf { item.sourceId == MediaSourceIds.JELLYFIN }
            ?.let { JellyfinWatchMutationKey(it.serverId, it.userId, item.id) }
        val detailMutationRevision = detailMutationKey?.let { jellyfinWatchStateRevisions[it] ?: 0L } ?: 0L
        _state.update {
            it.copy(
                selectedItem = withLocalResume(withConfirmedJellyfinWatchState(item.sourceId, listOf(item)).single()),
                selectedDownloadId = if (shouldPreferOfflineCopy) item.id else null,
                route = Route.MEDIA_DETAIL,
                errorMessage = null,
            )
        }
        // Enrich with full details (chapters power the seek-preview scrubber) in the background — the
        // gallery item carries only summary fields. Best-effort: if it fails (e.g. offline), or the user
        // has since selected a different item, the summary item is kept. A response begun before a local
        // watch-state mutation is also discarded rather than restoring stale progress in the detail view.
        viewModelScope.launch {
            activeSource().item(item.id).onSuccess { full ->
                _state.update { st ->
                    val detailWasSuperseded = detailMutationKey != null &&
                        (container.authRepository.currentUser.value != detailSession ||
                            jellyfinWatchStateRevisions[detailMutationKey] != detailMutationRevision)
                    if (st.selectedItem?.id != item.id || detailWasSuperseded) st else {
                        val detailed = withLocalResume(
                            withConfirmedJellyfinWatchState(item.sourceId, listOf(full)).single(),
                        )
                        st.copy(
                            selectedItem = detailed,
                            nowPlayingItem = if (st.nowPlayingItem?.id == item.id) detailed else st.nowPlayingItem,
                        )
                    }
                }
            }
        }
    }

    /** For an on-device local item, attach the stored "continue where you left off" position. */
    private fun withLocalResume(item: MediaItem): MediaItem =
        if (item.sourceId == MediaSourceIds.LOCAL) {
            item.copy(resumePositionSeconds = container.resumeStore.resumePosition(item.id))
        } else {
            item
        }

    /** Reload the current gallery view (libraries at the root, otherwise the current folder). */
    fun refreshGallery() {
        val target = _state.value.galleryTarget()
        viewModelScope.launch {
            refreshOnlineJellyfinSessionIfNeeded()
            container.authRepository.currentUser.value?.let(::schedulePendingJellyfinWatchStateReplay)
            if (_state.value.galleryTarget() != target) return@launch
            _state.update { current ->
                if (current.galleryTarget() == target) current.copy(galleryLoading = true, errorMessage = null) else current
            }
            launchGalleryLoad(target)
        }
    }

    /** A manual refresh is the explicit retry boundary for a cache-only Jellyfin session. */
    private suspend fun refreshOnlineJellyfinSessionIfNeeded() {
        val current = _state.value
        if (current.activeSourceId != MediaSourceIds.JELLYFIN || current.loggedIn || !current.hasCachedJellyfinSession) return
        if (container.authRepository.ensureOnlineSession().isSuccess) {
            container.jellyfinConnectionMonitor.markOnline()
        } else {
            container.jellyfinConnectionMonitor.markUnavailable()
        }
    }

    // ----- library search -----

    private var searchJob: Job? = null

    /** Debounced free-text search across the whole library; a blank query restores the normal view. */
    fun onSearchQueryChanged(query: String) {
        val sourceId = _state.value.activeSourceId
        _state.update { it.copy(searchQuery = query, searching = query.isNotBlank()) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val result = sourceFor(sourceId).search(query.trim())
            val stillCurrent = { _state.value.activeSourceId == sourceId && _state.value.searchQuery == query }
            if (!stillCurrent()) return@launch
            result.fold(
                onSuccess = { results ->
                    _state.update { current ->
                        if (current.activeSourceId == sourceId && current.searchQuery == query) {
                            current.copy(searchResults = withConfirmedJellyfinWatchState(sourceId, results), searching = false)
                        } else {
                            current
                        }
                    }
                },
                onFailure = { error ->
                    if (!stillCurrent()) return@fold
                    if (isSessionExpired(error)) handleSessionExpired()
                    else _state.update { current ->
                        if (current.activeSourceId == sourceId && current.searchQuery == query) {
                            current.copy(searching = false, errorMessage = libraryErrorMessage(error))
                        } else {
                            current
                        }
                    }
                },
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false) }
    }

    // ----- targets -----

    fun scanTargets() {
        if (!container.permissions.hasLocalNetworkAccess()) {
            onLocalNetworkPermissionDenied()
            return
        }
        // Enumerate both protocols. The framework chooser remains available, but the app-level Cast scan
        // is what makes Cast TVs visible alongside DLNA results without requiring an extra dialog tap.
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        route = Route.TARGET_PICKER,
                        isScanningTargets = true,
                        errorMessage = null,
                        castAvailable = container.castAvailable,
                        localNetworkPermissionGranted = true,
                    )
                }
                val castReady = container.awaitCastContext() != null
                val (castResult, dlnaResult) = coroutineScope {
                    val cast = async {
                        if (!castReady) {
                            Result.success(emptyList<DiscoveredTarget>())
                        } else {
                            try {
                                Result.success(container.castController.discover(CAST_SCAN_MS))
                            } catch (c: CancellationException) {
                                throw c
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }
                    val dlna = async {
                        try {
                            Result.success(container.dlnaController.discover(DLNA_SCAN_MS))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    cast.await() to dlna.await()
                }
                castResult.exceptionOrNull()?.let { container.logger.w("discovery", "Cast discovery failed", it) }
                dlnaResult.exceptionOrNull()?.let { container.logger.w("discovery", "DLNA discovery failed", it) }
                val cast = castResult.getOrDefault(emptyList())
                val dlna = dlnaResult.getOrDefault(emptyList())
                val physical = PhysicalTvAggregator.aggregate(
                    cast + dlna,
                    container.physicalTvAssociations,
                ).physicalTvs
                val resumeDevice = pendingResumeDeviceContext
                val resumeTv = resumeDevice?.let { context ->
                    PhysicalTvResumeMatcher.findConfident(
                        physical,
                        context.physicalDeviceStableId,
                        context.stableEndpointIdentity,
                    )
                }
                val resumeEndpoint = resumeTv?.let { tv ->
                    resumeDevice.lastSuccessfulProtocol
                        ?.let { protocol -> tv.availableEndpoints.firstOrNull { it.protocol == protocol } }
                        ?: tv.selectEndpoint()
                }
                val scanError = when {
                    castResult.isFailure && dlnaResult.isFailure ->
                        "Couldn't scan for TVs. Check the local network and try again."
                    castResult.isFailure ->
                        "Couldn't scan for Cast devices. Other smart TVs may still appear."
                    dlnaResult.isFailure ->
                        "Couldn't scan for other smart TVs. Cast devices may still appear."
                    else -> null
                }
                _state.update { current ->
                    val refreshedSelection = resumeTv ?: current.selectedPhysicalTv?.let { selected ->
                        physical.firstOrNull { it.id == selected.id }
                    }
                    current.copy(
                        castAvailable = castReady,
                        castTargets = cast,
                        dlnaTargets = dlna,
                        physicalTvs = physical,
                        selectedPhysicalTv = refreshedSelection,
                        selectedTarget = resumeEndpoint ?: refreshedSelection?.selectEndpoint(),
                        previousPhysicalTvName = when {
                            resumeTv != null -> null
                            resumeDevice != null -> resumeDevice.physicalDeviceReference
                            else -> current.previousPhysicalTvName
                        },
                        isScanningTargets = false,
                        errorMessage = scanError,
                    )
                }
                // Smart Resume gets one bounded discovery window. Exact stable identity can auto-reuse
                // the TV; a miss leaves the record intact and the picker visible with a display-only hint.
                if (resumeDevice != null) {
                    pendingResumeDeviceContext = null
                    if (resumeTv != null && resumeEndpoint != null) {
                        viewModelScope.launch {
                            delay(1)
                            if (_state.value.route == Route.TARGET_PICKER &&
                                _state.value.selectedPhysicalTv?.id == resumeTv.id
                            ) {
                                play().join()
                            }
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                container.logger.w("discovery", "Target scan couldn't start", e)
                _state.update {
                    it.copy(
                        isScanningTargets = false,
                        errorMessage = "Couldn't start device discovery. Check local-network access and try again.",
                    )
                }
            }
        }
    }

    fun selectTarget(target: DiscoveredTarget) {
        val physical = PhysicalTvAggregator.aggregate(
            _state.value.castTargets + _state.value.dlnaTargets + target,
            container.physicalTvAssociations,
        ).physicalTvs.firstOrNull { tv -> tv.availableEndpoints.any { it.protocol == target.protocol && it.id == target.id } }
        _state.update { it.copy(selectedPhysicalTv = physical, selectedTarget = target) }
    }

    /** One picker action chooses the physical screen; protocol selection stays internal. */
    fun selectPhysicalTv(target: PhysicalTv) {
        if (_state.value.playbackStartingTargetId != null) return
        pendingResumeDeviceContext = null
        _state.update {
            it.copy(
                selectedPhysicalTv = target,
                selectedTarget = target.selectEndpoint(),
                playbackStartingTargetId = target.id,
                previousPhysicalTvName = null,
            )
        }
        play()
    }

    /** Advanced correction for a false-positive association; the negative pair survives later scans. */
    fun unlinkPhysicalTv(target: PhysicalTv) {
        val cast = target.castEndpoint?.let(PhysicalEndpointKey::from) ?: return
        val dlna = target.dlnaEndpoint?.let(PhysicalEndpointKey::from) ?: return
        container.physicalTvAssociations.unlink(cast, dlna)
        val physical = PhysicalTvAggregator.aggregate(
            _state.value.castTargets + _state.value.dlnaTargets,
            container.physicalTvAssociations,
        ).physicalTvs
        _state.update {
            it.copy(physicalTvs = physical, selectedPhysicalTv = null, selectedTarget = null)
        }
        container.logger.event("discovery", "A saved TV endpoint association was unlinked")
    }

    fun play() = startPlayback(
        requestedItem = _state.value.selectedItem,
        downloadId = _state.value.selectedDownloadId,
    )

    /** Starts a gallery selection or queue entry without changing the item currently open for browsing. */
    private fun startPlayback(
        requestedItem: MediaItem?,
        downloadId: String?,
        onResult: (Boolean) -> Unit = {},
    ): Job = viewModelScope.launch {
        if (!container.permissions.hasLocalNetworkAccess()) {
            onLocalNetworkPermissionDenied()
            _state.update { it.copy(playbackStartingTargetId = null) }
            onResult(false)
            return@launch
        }
        // A fresh, user-initiated play supersedes any in-flight auto-reconnect.
        cancelReconnect()
        // Stop any in-flight device scan and its progress spinner (an infinite Compose animation) BEFORE
        // we foreground the proxy service — a still-animating picker would keep posting main-thread frames
        // and could starve the service's onStartCommand, blowing the startForegroundService() deadline.
        scanJob?.cancel()
        scanJob = null
        try {
            container.castController.stopDiscovery()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            container.logger.w("discovery", "Couldn't stop Cast discovery before playback", e)
        }
        if (_state.value.isScanningTargets) _state.update { it.copy(isScanningTargets = false) }
        reconnectContext = null
        // A new local/downloaded session must never inherit a previous online account's completion or
        // autoplay ownership. The online branch below installs its verified owner before streaming.
        onlinePlaybackOwner = null
        currentResumeKey = null
        currentDurationSeconds = null
        lastResumeSaveMs = 0L
        // Never let an initial failure/alternate-protocol attempt inherit the previous item's position.
        lastPlaybackPositionSeconds = 0L
        val requestedSmartResumePosition = smartResumeOverrideSeconds
        val s = _state.value
        val physicalTv = s.selectedPhysicalTv ?: run {
            _state.update { it.copy(playbackStartingTargetId = null) }
            onResult(false)
            return@launch
        }
        val target = physicalTv.selectEndpoint() ?: run {
            _state.update { it.copy(playbackStartingTargetId = null) }
            onResult(false)
            return@launch
        }
        val resumeDeviceContext = smartResumeDeviceContext(physicalTv, target)
        _state.update { it.copy(selectedTarget = target, nowPlayingItem = requestedItem) }
        val controller = if (target.protocol == Protocol.CAST) container.castController else container.dlnaController
        // Navigate to the playback screen only AFTER the proxy service enters the foreground (via the
        // onForegrounded callback below), so the screen's recomposition can't queue ahead of onStartCommand
        // on the main thread and blow the startForegroundService() deadline
        // (ForegroundServiceDidNotStartInTimeException on a Galaxy S24 / Android 16). See PlaybackEngine.
        val pendingPlayback = PlaybackUiState(
            targetName = physicalTv.displayName,
            protocol = target.protocol.name,
            mediaTitle = requestedItem?.title ?: downloadId?.let { itemId ->
                activeDownloadOwner()?.let { owner ->
                    downloadTitles[DownloadIdentity(owner, itemId)] ?: container.downloader.titleFor(itemId, owner)
                }
            }.orEmpty(),
            durationSeconds = requestedItem?.runtimeSeconds,
            phase = PlaybackPhase.CONNECTING,
            adaptiveNote = "Connecting to the TV…",
            volumeSupported = false,
        )
        val goToPlayback = {
            _state.update {
                it.copy(
                    route = Route.PLAYBACK,
                    playback = it.playback ?: pendingPlayback,
                    errorMessage = null,
                )
            }
        }
        playbackStarting = true
        var started = false
        var onlineOwnerForAttempt: UserSession? = null
        runCatching {
            if (downloadId != null) {
                val owner = activeDownloadOwner() ?: error("The Jellyfin account for this download is unavailable.")
                check(!hasPendingJellyfinWatchStateMutation(owner.serverId, owner.userId, downloadId)) {
                    "This item's watch-state change is waiting to sync. Reconnect to Jellyfin before playing it."
                }
                val entry = container.downloadStore.get(owner, downloadId) ?: error("Download no longer available.")
                val file = container.downloadStore.fileFor(entry)
                check(file.isFile && file.canRead() && file.length() > 0L &&
                    (entry.sizeBytes <= 0L || file.length() == entry.sizeBytes)) {
                    "The downloaded copy is unavailable."
                }
                val resumeKey = DownloadIdentity(owner, downloadId).resumeKey
                val resumeAt = PlaybackPositionPolicy.clamp(
                    requestedSmartResumePosition ?: container.resumeStore.resumePosition(resumeKey) ?: 0L,
                    entry.runtimeSeconds,
                )
                reconnectContext = ReconnectContext.Local(
                    file.absolutePath, entry.mimeType, entry.title, entry.runtimeSeconds,
                    physicalTv, target, downloadId, downloadedSmartResumeSeed(entry),
                )
                // Install ownership before the renderer starts. Early status, immediate Stop, and an
                // alternate-protocol recovery must all checkpoint this exact download, never the prior item.
                currentResumeKey = resumeKey
                currentDurationSeconds = entry.runtimeSeconds
                lastPlaybackPositionSeconds = resumeAt
                container.playbackEngine.playLocal(
                    filePath = file.absolutePath,
                    contentType = entry.mimeType,
                    title = entry.title,
                    runtimeSeconds = entry.runtimeSeconds,
                    allowClientTranscode = container.playbackPreferences.transcodeLocalOnDevice,
                    resumePositionSeconds = resumeAt,
                    smartResumeSeed = downloadedSmartResumeSeed(entry),
                    smartResumeDeviceContext = resumeDeviceContext,
                    target = controller,
                    selectedTarget = target,
                    onForegrounded = goToPlayback,
                )
            } else {
                val item = requestedItem ?: error("Nothing selected to play.")
                if (item.sourceId == MediaSourceIds.LOCAL) {
                    // On-device file: served via the proxy from a content:// fd. Resumes where you left
                    // off and auto-reconnects, the same as an online session.
                    val mime = container.localMediaSource.mimeTypeFor(item.id)
                    val resumeAt = PlaybackPositionPolicy.clamp(
                        requestedSmartResumePosition ?: container.resumeStore.resumePosition(item.id) ?: 0L,
                        item.runtimeSeconds,
                    )
                    reconnectContext = ReconnectContext.Local(
                        item.id, mime, item.title, item.runtimeSeconds,
                        physicalTv, target, null, localSmartResumeSeed(item),
                    )
                    currentResumeKey = item.id
                    currentDurationSeconds = item.runtimeSeconds
                    lastPlaybackPositionSeconds = resumeAt
                    container.playbackEngine.playLocal(
                        filePath = item.id,
                        contentType = mime,
                        title = item.title,
                        runtimeSeconds = item.runtimeSeconds,
                        allowClientTranscode = container.playbackPreferences.transcodeLocalOnDevice,
                        resumePositionSeconds = resumeAt,
                        smartResumeSeed = localSmartResumeSeed(item),
                        smartResumeDeviceContext = resumeDeviceContext,
                        target = controller,
                        selectedTarget = target,
                        onForegrounded = goToPlayback,
                    )
                } else {
                    check(s.availabilityFor(item) != JellyfinItemAvailability.UNAVAILABLE) {
                        "Jellyfin is unavailable. Download this item to play it offline."
                    }
                    // Keep an online connection alive only when episode autoplay or this session's playlist
                    // has a concrete next item ready for a seamless hand-off. Capture the verified owner
                    // before any stream request so an account rebind can invalidate this whole session.
                    val owner = container.authRepository.currentUser.value
                        ?: error("Reconnect to Jellyfin before streaming this item.")
                    check(!hasPendingJellyfinWatchStateMutation(owner.serverId, owner.userId, item.id)) {
                        "This item's watch-state change is waiting to sync. Reconnect to Jellyfin before playing it."
                    }
                    onlineOwnerForAttempt = owner
                    onlinePlaybackOwner = owner
                    val autoAdvance = shouldAutoAdvance(item, s.playlist)
                    val resumeAt = PlaybackPositionPolicy.clamp(
                        requestedSmartResumePosition
                            ?: durableOnlineResumePosition(item, owner)
                            ?: 0L,
                        item.runtimeSeconds,
                    )
                    lastPlaybackPositionSeconds = resumeAt
                    reconnectContext = ReconnectContext.Online(item, physicalTv, target, owner)
                    container.playbackEngine.play(
                        item, controller, target,
                        streamPreferences(item),
                        // Prefer the exact, atomic renderer checkpoint for this item/account. Jellyfin's
                        // server resume remains the fallback and is refreshed by the next heartbeat.
                        resumePositionOverrideSeconds = resumeAt,
                        autoAdvance = autoAdvance,
                        smartResumeSeed = onlineSmartResumeSeed(item, owner),
                        smartResumeDeviceContext = resumeDeviceContext,
                        onForegrounded = goToPlayback,
                    )
                }
            }
        }.onSuccess {
            val owner = onlineOwnerForAttempt
            if (owner == null || isActiveOnlinePlaybackOwner(owner)) {
                smartResumeOverrideSeconds = null
                started = true
            } else {
                // The transition path waited for engine.stop() before rebinding. Do not let this late
                // startup completion resurrect the now-invalid session or stop a newer account's play.
                container.logger.event("playback", "Discarded a play start after its Jellyfin account changed")
            }
        }.onFailure { e ->
            if (e is CancellationException) {
                throw e
            } else if (isSessionExpired(e)) {
                if (onlinePlaybackOwner == onlineOwnerForAttempt) onlinePlaybackOwner = null
                handleSessionExpired()
            } else {
                // Surface the ACTUAL cause (redacted) instead of a misleading "same Wi-Fi" message.
                // A Jellyfin HTTP failure is already logged with its parsed reason at the jellyfin layer
                // (JellyfinClient.exec); only log here for non-HTTP failures (Cast/DLNA/proxy) so the
                // diagnostics export doesn't carry the same error twice.
                val reason = LogRedactor.redact(bestFailureReason(e)).take(180)
                val latestFailureCause = container.playbackEngine.recoverySnapshot().attempts.lastOrNull()?.failureCause
                val upstreamFailure = latestFailureCause == PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE ||
                    generateSequence(e) { it.cause }.any {
                        it is JellyfinHttpException || it is PlaybackPreparationException
                    }
                if (!upstreamFailure) {
                    container.logger.w("Playback", "Couldn't start playback", e)
                }
                val context = reconnectContext
                var switched = false
                if (context != null && !upstreamFailure) {
                    val generation = ++reconnectGeneration
                    reconnecting = true
                    _state.update { current ->
                        current.copy(
                            route = Route.PLAYBACK,
                            playback = (current.playback ?: pendingPlayback).copy(
                                phase = PlaybackPhase.CHANGING_PROTOCOL,
                                adaptiveNote = "Trying another connection to the same TV…",
                                reconnecting = true,
                            ),
                            errorMessage = null,
                        )
                    }
                    val latest = container.playbackEngine.recoverySnapshot().attempts.lastOrNull()
                    switched = tryAlternateProtocol(
                        context,
                        failureStage = latest?.failureStage ?: PlaybackFailureStage.UNKNOWN,
                        failureCause = latest?.failureCause ?: PlaybackFailureCause.UNKNOWN,
                        generation = generation,
                    )
                    if (generation != reconnectGeneration) return@onFailure
                    reconnecting = false
                    _state.update { current ->
                        current.copy(playback = current.playback?.copy(reconnecting = false))
                    }
                }
                if (switched && (onlineOwnerForAttempt == null || isActiveOnlinePlaybackOwner(onlineOwnerForAttempt))) {
                    smartResumeOverrideSeconds = null
                    started = true
                    container.logger.event("playback", "Initial playback recovered through the TV's alternate protocol")
                } else if (context != null) {
                    if (onlinePlaybackOwner == onlineOwnerForAttempt) onlinePlaybackOwner = null
                    // Keep raw/redacted transport detail in diagnostics; the main UI stays concise.
                    container.logger.event("playback", "Initial playback exhausted: $reason")
                    surfaceTerminalPlaybackFailure(context, "Couldn't start playback on this TV.")
                } else {
                    if (onlinePlaybackOwner == onlineOwnerForAttempt) onlinePlaybackOwner = null
                    _state.update { current ->
                        current.copy(
                            route = Route.PLAYBACK,
                            playback = (current.playback ?: pendingPlayback).copy(
                                phase = PlaybackPhase.FAILED,
                                isTerminal = true,
                                errorMessage = "Couldn't start playback on this TV.",
                            ),
                            errorMessage = null,
                        )
                    }
                }
            }
        }
        playbackStarting = false
        _state.update { it.copy(selectedDownloadId = null, playbackStartingTargetId = null) }
        onResult(started)
    }

    /**
     * A genuine end-of-media finished. An explicit playlist always wins over episode-autoplay. Online
     * items reuse the renderer when possible; downloaded/local entries fall back to a normal fresh start.
     */
    private fun onEndOfMedia(completion: PlaybackCompletion) {
        val snapshot = _state.value
        // For online media the engine carries an immutable item identity, so a delayed event can never mark
        // a newer item that reused the same TV. It also needs the account that started it: the client is a
        // singleton and may already be rebound by the time an old renderer emits its final event.
        val completedItem = completion.item?.takeIf { completion.isJellyfinSession } ?: snapshot.nowPlayingItem
        val completionOwner = onlinePlaybackOwner
        if (completion.isJellyfinSession &&
            (!container.playbackEngine.isCurrentPlaybackGeneration(completion.generation) ||
                !isActiveOnlinePlaybackOwner(completionOwner))
        ) {
            container.logger.event("playback", "Ignoring a stale completed Jellyfin item")
            return
        }
        if (completion.isJellyfinSession && completedItem?.sourceId == MediaSourceIds.JELLYFIN) {
            mutateJellyfinWatchState(
                item = completedItem,
                origin = "playback completion",
                mutationKind = JellyfinWatchMutationKind.MARK_PLAYED,
                rejectIfPlaying = false,
            )
        }
        if (!completion.isJellyfinSession) clearCompletedLocalResume()

        if (autoAdvancing) {
            container.logger.event("playlist", "Ignoring duplicate end-of-media signal during a hand-off")
            return
        }
        snapshot.playlist.next?.let { next ->
            autoAdvancing = true
            viewModelScope.launch {
                try {
                    advancePlaylist(next, currentEnded = true, trigger = "Autoplaying queued item")
                } finally {
                    autoAdvancing = false
                }
            }
            return
        }

        // A downloaded/local completion is allowed to advance an explicit local playlist above, but must
        // not issue a network next-episode lookup after the user has switched Jellyfin accounts.
        if (!completion.isJellyfinSession) return
        val owner = completionOwner ?: return
        val current = completedItem ?: return
        if (!container.playbackPreferences.autoPlayNextEpisode ||
            current.sourceId != MediaSourceIds.JELLYFIN ||
            !current.type.equals("Episode", ignoreCase = true) ||
            snapshot.selectedTarget == null
        ) return
        val seriesId = current.seriesId ?: return
        autoAdvancing = true
        viewModelScope.launch {
            try {
                var next: MediaItem? = null
                val requestAccepted = runCatching {
                    container.authRepository.runWithActiveSession(owner) {
                        next = container.jellyfinClient.nextEpisode(seriesId, current.id)
                    }
                }.onFailure { error -> if (isSessionExpired(error)) handleSessionExpired() }.getOrDefault(false)
                if (!requestAccepted || !isActiveOnlinePlaybackOwner(owner)) {
                    container.logger.event("playback", "Skipped next-episode autoplay after its Jellyfin account changed")
                    return@launch
                }
                val nextItem = next
                if (nextItem != null) {
                    if (isPlaybackBlockedByPendingJellyfinWatchState(nextItem, owner)) {
                        container.logger.event("playback", "Blocked next-episode autoplay pending a watch-state change")
                        runCatching { container.playbackEngine.stop() }
                        _state.update {
                            it.copy(
                                route = Route.GALLERY,
                                playback = null,
                                nowPlayingItem = null,
                                errorMessage = "${nextItem.title}'s watch-state change is waiting to sync. Reconnect to Jellyfin before playing it.",
                            )
                        }
                        return@launch
                    }
                    container.logger.event("playback", "Autoplaying next episode")
                    _state.update { it.copy(nowPlayingItem = nextItem, selectedDownloadId = null) }
                    val previousContext = reconnectContext as? ReconnectContext.Online
                    val canReuseConnection = previousContext?.owner == owner && isActiveOnlinePlaybackOwner(owner)
                    val seamlessTarget = if (canReuseConnection) {
                        _state.value.selectedTarget?.takeIf {
                            runCatching {
                                container.playbackEngine.playNext(
                                    nextItem,
                                    streamPreferences(nextItem),
                                    onlineSmartResumeSeed(nextItem, owner),
                                    autoAdvance = shouldAutoAdvance(nextItem, _state.value.playlist),
                                )
                            }.onFailure { error -> if (isSessionExpired(error)) handleSessionExpired() }.getOrDefault(false)
                        }
                    } else {
                        null
                    }
                    if (seamlessTarget != null && previousContext != null && isActiveOnlinePlaybackOwner(owner)) {
                        reconnectContext = ReconnectContext.Online(nextItem, previousContext.physicalTv, seamlessTarget, owner)
                    } else if (isActiveOnlinePlaybackOwner(owner)) {
                        startPlayback(nextItem, null).join()
                    }
                } else {
                    onlinePlaybackOwner = null
                    runCatching { container.playbackEngine.stop() }
                    _state.update { it.copy(route = Route.GALLERY, playback = null, nowPlayingItem = null) }
                    loadContinueWatching()
                }
            } finally {
                autoAdvancing = false
            }
        }
    }

    /** Advance to [entry], retaining it on a failed start so a transient outage never silently drops it. */
    private suspend fun advancePlaylist(
        entry: PlaylistEntry,
        currentEnded: Boolean,
        trigger: String,
    ) {
        val snapshot = _state.value
        if (snapshot.playlist.next?.entryId != entry.entryId) return
        val item = entry.item
        val availability = snapshot.availabilityFor(item)
        val pendingWatchState = isPlaybackBlockedByPendingJellyfinWatchState(item, activeJellyfinSession())
        if (availability == JellyfinItemAvailability.UNAVAILABLE || pendingWatchState) {
            val message = if (pendingWatchState) {
                "${item.title}'s watch-state change is waiting to sync. Reconnect to Jellyfin before playing it."
            } else {
                "${item.title} is unavailable while Jellyfin is offline. Reconnect or download it first."
            }
            container.logger.event("playlist", if (pendingWatchState) {
                "Queued item blocked by a pending watch-state change: ${item.title}"
            } else {
                "Queued item unavailable: ${item.title}"
            })
            _state.update { current ->
                val withMessage = current.copy(errorMessage = message)
                if (currentEnded) {
                    withMessage.copy(
                        playback = null,
                        nowPlayingItem = null,
                        route = if (current.route == Route.PLAYBACK) Route.GALLERY else current.route,
                    )
                } else {
                    withMessage
                }
            }
            if (currentEnded) runCatching { container.playbackEngine.stop() }
            return
        }

        val shouldUseOfflineCopy = availability == JellyfinItemAvailability.DOWNLOADED &&
            snapshot.jellyfinLibraryStatus == com.adsamcik.streamferry.domain.JellyfinLibraryStatus.UNAVAILABLE
        val remaining = snapshot.playlist.remove(entry.entryId)
        val target = snapshot.selectedTarget
        val previousOnlineContext = reconnectContext as? ReconnectContext.Online
        if (item.sourceId == MediaSourceIds.JELLYFIN && previousOnlineContext != null &&
            !isActiveOnlinePlaybackOwner(previousOnlineContext.owner)
        ) {
            container.logger.event("playlist", "Skipped a stale Jellyfin queue hand-off after account change")
            return
        }
        val canReuseConnection = !shouldUseOfflineCopy && item.sourceId == MediaSourceIds.JELLYFIN &&
            previousOnlineContext != null && target != null &&
            isActiveOnlinePlaybackOwner(previousOnlineContext.owner)
        if (canReuseConnection) {
            val owner = requireNotNull(previousOnlineContext).owner
            val seamless = runCatching {
                container.playbackEngine.playNext(
                    item,
                    streamPreferences(item),
                    onlineSmartResumeSeed(item, owner),
                    autoAdvance = shouldAutoAdvance(item, remaining),
                )
            }.onFailure { error ->
                if (isSessionExpired(error)) handleSessionExpired()
            }.getOrDefault(false)
            if (seamless && isActiveOnlinePlaybackOwner(owner)) {
                reconnectContext = ReconnectContext.Online(item, previousOnlineContext.physicalTv, target, owner)
                _state.update { current ->
                    if (current.playlist.next?.entryId != entry.entryId) current else current.copy(
                        nowPlayingItem = item,
                        playlist = current.playlist.remove(entry.entryId),
                        selectedDownloadId = null,
                        errorMessage = null,
                    )
                }
                refreshAutoAdvancePolicy()
                container.logger.event("playlist", "$trigger seamlessly: ${item.title}")
                return
            }
        }
        // A rebind can begin while playNext is waiting on the renderer. Do not fall through and start
        // this old queue entry with the newly selected account after that owner was invalidated.
        if (previousOnlineContext != null && !isActiveOnlinePlaybackOwner(previousOnlineContext.owner)) {
            container.logger.event("playlist", "Aborted a queue hand-off after its Jellyfin account changed")
            return
        }

        // Local/downloaded items, or an already-released renderer, need a fresh start. Remove only after
        // success, which keeps a failed request visible and retryable in the playlist.
        startPlayback(
            requestedItem = item,
            downloadId = if (shouldUseOfflineCopy) item.id else null,
            onResult = { started ->
                if (started) {
                    _state.update { current ->
                        if (current.playlist.entries.none { it.entryId == entry.entryId }) current else current.copy(
                            playlist = current.playlist.remove(entry.entryId),
                            nowPlayingItem = item,
                            errorMessage = null,
                        )
                    }
                    refreshAutoAdvancePolicy()
                    container.logger.event("playlist", "$trigger: ${item.title}")
                }
            },
        ).join()
    }

    /** Resume the app-wide renderer-confirmed checkpoint through the normal target picker. */
    fun resumeSmartResume() = viewModelScope.launch {
        val record = container.smartResumeStore.current ?: return@launch
        if (isSmartResumeBlockedByWatchMutation(record)) {
            _state.update { it.copy(errorMessage = "This item's watch-state change is waiting to sync. Reconnect to Jellyfin before resuming it.") }
            return@launch
        }
        runCatching {
            when (record.sourceType) {
                SmartResumeSourceType.JELLYFIN -> {
                    requireRecordOwner(record)
                    val item = container.jellyfinMediaSource.item(record.mediaId).getOrThrow()
                    val position = SmartResumePositionReconciler.reconcile(
                        record,
                        rendererConfirmedSeconds = null,
                        jellyfinResumeSeconds = item.resumePositionSeconds,
                    ) ?: error("This playback is already complete.")
                    beginSmartResumeTargetSelection(item, null, position, record.deviceContext())
                }
                SmartResumeSourceType.LOCAL -> {
                    val uri = record.localContentUri ?: error("This local video is no longer available.")
                    val item = container.localMediaSource.item(uri).getOrThrow()
                    val position = SmartResumePositionReconciler.reconcile(
                        record,
                        rendererConfirmedSeconds = container.resumeStore.resumePosition(uri),
                        jellyfinResumeSeconds = null,
                    ) ?: error("This playback is already complete.")
                    beginSmartResumeTargetSelection(item, null, position, record.deviceContext())
                }
                SmartResumeSourceType.DOWNLOADED -> {
                    requireRecordOwner(record)
                    val owner = downloadOwnerFor(record)
                    val entry = container.downloadStore.get(owner, record.mediaId) ?: error("The downloaded copy was deleted.")
                    val file = container.downloadStore.fileFor(entry)
                    check(file.isFile && file.canRead() && file.length() > 0L) { "The downloaded copy is unavailable." }
                    val position = SmartResumePositionReconciler.reconcile(
                        record,
                        rendererConfirmedSeconds = container.resumeStore.resumePosition(
                            DownloadIdentity(owner, entry.itemId).resumeKey,
                        ),
                        jellyfinResumeSeconds = null,
                    ) ?: error("This playback is already complete.")
                    beginSmartResumeTargetSelection(null, entry.itemId, position, record.deviceContext())
                }
            }
        }.onFailure { error ->
            _state.update { it.copy(errorMessage = error.message ?: "This Smart Resume item is unavailable.") }
        }
    }

    fun dismissSmartResume() = container.smartResumeStore.clear()

    private fun beginSmartResumeTargetSelection(
        item: MediaItem?,
        downloadId: String?,
        position: Long,
        deviceContext: SmartResumeDeviceContext,
    ) {
        smartResumeOverrideSeconds = position
        pendingResumeDeviceContext = deviceContext
        _state.update {
            it.copy(
                selectedItem = item ?: it.selectedItem,
                selectedDownloadId = downloadId,
                activeSourceId = item?.sourceId ?: it.activeSourceId,
                errorMessage = null,
                previousPhysicalTvName = deviceContext.physicalDeviceReference,
            )
        }
        scanTargets()
    }

    private fun requireRecordOwner(record: SmartResumeRecord) {
        val current = activeJellyfinSession()
        check(current?.serverId == record.serverId && current?.userId == record.userId) {
            "Sign in to the Jellyfin account that played this video to resume it."
        }
    }

    private fun downloadOwnerFor(record: SmartResumeRecord): DownloadOwner = DownloadOwner(
        serverId = record.serverId ?: error("The saved Jellyfin server is unavailable."),
        userId = record.userId ?: error("The saved Jellyfin user is unavailable."),
    )

    /** A cached session is sufficient to identify and play its local downloaded copies. */
    private fun activeJellyfinSession() =
        container.authRepository.currentUser.value ?: container.authRepository.cachedSession.value

    private fun activeDownloadOwner(): DownloadOwner? = activeJellyfinSession()
        ?.let { DownloadOwner(serverId = it.serverId, userId = it.userId) }

    private fun onlineSmartResumeSeed(item: MediaItem, owner: UserSession): SmartResumeSeed =
        SmartResumeSeed(
            SmartResumeSourceType.JELLYFIN,
            item.id,
            item.title,
            item.subtitle,
            item.runtimeSeconds,
            owner.serverId,
            owner.userId,
        )

    private fun downloadedSmartResumeSeed(entry: DownloadEntry): SmartResumeSeed? =
        (entry.owner ?: activeDownloadOwner())?.let { owner ->
            SmartResumeSeed(
                SmartResumeSourceType.DOWNLOADED,
                entry.itemId,
                entry.title,
                durationSeconds = entry.runtimeSeconds,
                serverId = owner.serverId,
                userId = owner.userId,
            )
        }

    private fun localSmartResumeSeed(item: MediaItem): SmartResumeSeed =
        SmartResumeSeed(SmartResumeSourceType.LOCAL, item.id, item.title, item.subtitle, item.runtimeSeconds, localContentUri = item.id)

    // ----- offline downloads -----

    fun downloadItem(item: MediaItem, format: DownloadFormat = DownloadFormat.Original) {
        val owner = activeDownloadOwner()
        if (!_state.value.loggedIn || owner == null) {
            _state.update { it.copy(errorMessage = "Reconnect to Jellyfin before starting a download.") }
            return
        }
        val identity = DownloadIdentity(owner, item.id)
        downloadTitles[identity] = item.title
        // Enqueue (this persists the request and sets the Queued state) BEFORE starting the service, so
        // the service's idle check always observes the new download and can't stop before it begins.
        container.downloader.download(item, format, owner)
        container.startDownloadService()
    }

    fun downloadSelected(format: DownloadFormat = DownloadFormat.Original) {
        _state.value.selectedItem?.let { downloadItem(it, format) }
    }

    fun cancelDownload(itemId: String) {
        activeDownloadOwner()?.let { owner -> container.downloader.cancel(itemId, owner) }
    }

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        activeDownloadOwner()?.let { owner -> container.downloader.delete(itemId, owner) }
        refreshDownloadEntries()
    }

    fun openDownloads() = viewModelScope.launch {
        val snapshot = _state.value
        val origin = NavigationStatePolicy.captureDownloadsOrigin(
            currentRoute = snapshot.route,
            previousOrigin = snapshot.downloadsBackRoute,
            availability = NavigationStatePolicy.Availability(
                hasActivePlayback = snapshot.playback != null,
                hasSelectedItem = snapshot.selectedItem != null,
            ),
        )
        refreshDownloadEntries()
        _state.update {
            it.copy(
                route = Route.DOWNLOADS,
                downloadsBackRoute = origin,
                errorMessage = null,
            )
        }
    }

    /** Begin casting a downloaded copy offline: remember it, then trigger device scan + picker. */
    fun prepareCastDownload(itemId: String) = _state.update {
        it.copy(selectedDownloadId = itemId)
    }

    /** Clear any pending offline-cast selection (used when starting a normal online cast). */
    fun clearDownloadSelection() = _state.update { it.copy(selectedDownloadId = null) }

    /** Queue a playable item without changing the gallery/detail selection or interrupting current playback. */
    fun enqueue(item: MediaItem) {
        val queuedItem = withLocalResume(item)
        val snapshot = _state.value
        val reason = when {
            queuedItem.isFolder -> "Open this title and choose a specific episode or movie to add."
            snapshot.availabilityFor(queuedItem) == JellyfinItemAvailability.UNAVAILABLE ->
                "${queuedItem.title} is unavailable while Jellyfin is offline."
            else -> null
        }
        if (reason != null) {
            _state.update { it.copy(errorMessage = reason) }
            return
        }
        val entryId = ++playlistEntrySequence
        _state.update { current ->
            current.copy(playlist = current.playlist.enqueue(entryId, queuedItem), errorMessage = null)
        }
        refreshAutoAdvancePolicy()
    }

    fun enqueueSelected() {
        _state.value.selectedItem?.let(::enqueue)
    }

    fun removePlaylistEntry(entryId: Long) {
        _state.update { current -> current.copy(playlist = current.playlist.remove(entryId)) }
        refreshAutoAdvancePolicy()
    }

    fun clearPlaylist() {
        _state.update { current -> current.copy(playlist = current.playlist.clear()) }
        refreshAutoAdvancePolicy()
    }

    /** Skip to the first queued item, saving any local resume point before replacing the renderer stream. */
    fun skipToNextPlaylistItem() {
        if (autoAdvancing) return
        val next = _state.value.playlist.next ?: return
        autoAdvancing = true
        viewModelScope.launch {
            try {
                saveResumeNow()
                advancePlaylist(next, currentEnded = false, trigger = "Skipped to queued item")
            } finally {
                autoAdvancing = false
            }
        }
    }

    fun togglePlayPause() = viewModelScope.launch {
        try {
            container.playbackEngine.togglePlayPause()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change playback", e)
        }
    }

    fun seekTo(positionSeconds: Long) = viewModelScope.launch {
        try {
            container.playbackEngine.seekTo(positionSeconds)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("seek", e)
        }
    }

    fun adjustVolume(direction: Int) = viewModelScope.launch {
        try {
            container.playbackEngine.adjustVolume(direction)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change volume", e)
        }
    }

    fun setVolume(level: Float) = viewModelScope.launch {
        try {
            container.playbackEngine.setVolume(level)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change volume", e)
        }
    }

    /** Surface sender-command failures instead of leaving an optimistic TV control state on screen. */
    private fun handlePlaybackControlFailure(operation: String, error: Exception) {
        if (isSessionExpired(error)) {
            handleSessionExpired()
            return
        }
        container.logger.w("playback", "Couldn't $operation on the TV", error)
        val message = "Couldn't $operation on the TV. Please try again."
        _state.update { current ->
            current.copy(
                errorMessage = message,
                playback = current.playback?.copy(errorMessage = message),
            )
        }
    }

    /** Switch the audio track (null = server default). Re-resolves the stream, so recover an expired token. */
    fun selectAudioTrack(index: Int?) = viewModelScope.launch {
        try {
            container.playbackEngine.selectAudioTrack(index)
            // Remember the chosen audio LANGUAGE only after the replacement stream loaded successfully.
            rememberShowLanguage { key ->
                _state.value.playback?.audioTracks?.firstOrNull { it.index == index }?.language
                    ?.let { container.showLanguageStore.rememberAudio(key, it) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change audio track", e)
        }
    }

    /** Enable subtitles at [index] (burned in) or disable them (null). Re-resolves the stream. */
    fun selectSubtitleTrack(index: Int?) = viewModelScope.launch {
        try {
            container.playbackEngine.selectSubtitleTrack(index)
            // Remember OFF/language only after the replacement stream loaded successfully.
            rememberShowLanguage { key ->
                if (index == null) {
                    container.showLanguageStore.rememberSubtitle(key, null)
                } else {
                    _state.value.playback?.subtitleTracks?.firstOrNull { it.index == index }?.language
                        ?.let { container.showLanguageStore.rememberSubtitle(key, it) }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change subtitles", e)
        }
    }

    /** Run [remember] with the current show's memory key (series id for episodes, else item id). No-op if none. */
    private inline fun rememberShowLanguage(remember: (showKey: String) -> Unit) {
        showLanguageKey(_state.value.nowPlayingItem)?.let(remember)
    }

    /** Pin a specific streaming quality (bitrate), or return to Auto (null). Re-resolves the stream. */
    fun selectQuality(bitrateBps: Long?) = viewModelScope.launch {
        try {
            container.playbackEngine.selectQuality(bitrateBps)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change playback quality", e)
        }
    }

    /** Override the stream resolution for this playback only; null returns to the saved automatic cap. */
    fun selectMaxVideoHeight(height: Int?) = viewModelScope.launch {
        try {
            container.playbackEngine.selectMaxVideoHeight(height)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change video resolution", e)
        }
    }

    fun skipBy(deltaSeconds: Long) = viewModelScope.launch {
        try {
            container.playbackEngine.skip(deltaSeconds)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("seek", e)
        }
    }

    /** Pin the transcode video codec (e.g. "hevc"), or return to automatic (null). Re-resolves the stream. */
    fun selectPreferredCodec(codec: String?) = viewModelScope.launch {
        try {
            container.playbackEngine.selectPreferredCodec(codec)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("change video codec", e)
        }
    }

    /** Skip the intro/outro/recap segment covering the current position (the "Skip" button). */
    fun skipSegment() = viewModelScope.launch {
        try {
            container.playbackEngine.skipActiveSegment()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            handlePlaybackControlFailure("skip the segment", e)
        }
    }

    /** Mark a Jellyfin episode or movie watched/unwatched using its native per-user state. */
    fun markWatched(item: MediaItem, played: Boolean) {
        // Never send a series/season mutation here: Jellyfin cascades it into child episodes, which can
        // race an active playback report for one of those children. This screen exposes leaf-item actions.
        if (item.sourceId != MediaSourceIds.JELLYFIN || item.isFolder) return
        mutateJellyfinWatchState(
            item = item,
            origin = "manual watch-state change",
            mutationKind = if (played) {
                JellyfinWatchMutationKind.MARK_PLAYED
            } else {
                JellyfinWatchMutationKind.MARK_UNPLAYED
            },
        )
    }

    /** Reset a Jellyfin episode to unwatched at 0:00, including every durable local resume representation. */
    fun resetEpisodeProgress(item: MediaItem) {
        if (!item.isJellyfinEpisode()) return
        mutateJellyfinWatchState(
            item = item,
            origin = "manual progress reset",
            mutationKind = JellyfinWatchMutationKind.RESET_PROGRESS,
        )
    }

    /**
     * Write the user's absolute intent before touching Jellyfin. If the process dies at any later point,
     * [JellyfinWatchMutationStore] keeps the local state coherent and safely retries only for this exact
     * verified account. This avoids a stale resume checkpoint winning over an explicit watched/reset action.
     */
    private fun mutateJellyfinWatchState(
        item: MediaItem,
        origin: String,
        mutationKind: JellyfinWatchMutationKind,
        rejectIfPlaying: Boolean = true,
    ) {
        if (item.sourceId != MediaSourceIds.JELLYFIN || (rejectIfPlaying && item.isFolder)) return
        if (rejectIfPlaying && isPlaybackActiveFor(item.id)) {
            _state.update { current ->
                current.copy(errorMessage = "Stop this item before changing its watch state.")
            }
            return
        }
        val session = container.authRepository.currentUser.value ?: return
        val mutation = JellyfinWatchMutation(
            operationId = UUID.randomUUID().toString(),
            serverId = session.serverId,
            userId = session.userId,
            itemId = item.id,
            kind = mutationKind,
            createdAtMillis = System.currentTimeMillis(),
        )
        val mutationKey = mutation.key()
        watchStateMutationScope.launch {
            if (!activeWatchStateMutations.add(mutationKey)) return@launch
            setWatchStateMutationUpdating(mutation, updating = true)
            try {
                withContext(Dispatchers.IO) { container.jellyfinWatchMutationStore.put(mutation) }
                synchronizeJellyfinWatchStateMutation(mutation, session, origin)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                handleJellyfinWatchStateMutationFailure(session, origin, error)
            } finally {
                activeWatchStateMutations.remove(mutationKey)
                setWatchStateMutationUpdating(mutation, updating = false)
            }
        }
    }

    /** Replay pending entries only after this account has been verified and installed in the singleton client. */
    private fun schedulePendingJellyfinWatchStateReplay(session: UserSession) {
        if (container.authRepository.currentUser.value != session) return
        val pending = container.jellyfinWatchMutationStore.pendingFor(session.serverId, session.userId)
        if (pending.isEmpty()) return
        watchStateMutationScope.launch {
            pending.forEach { mutation ->
                if (container.authRepository.currentUser.value != session) return@launch
                val mutationKey = mutation.key()
                if (!activeWatchStateMutations.add(mutationKey)) return@forEach
                setWatchStateMutationUpdating(mutation, updating = true)
                try {
                    synchronizeJellyfinWatchStateMutation(mutation, session, "queued watch-state change")
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    handleJellyfinWatchStateMutationFailure(session, "queued watch-state change", error)
                    // An expired token queues no useful additional requests; the normal expiry path tears the
                    // session down and a later verified sign-in will resume the durable journal.
                    if (isSessionExpired(error)) return@launch
                } finally {
                    activeWatchStateMutations.remove(mutationKey)
                    setWatchStateMutationUpdating(mutation, updating = false)
                }
            }
        }
    }

    /**
     * Reapply persistent local state, submit once, then acknowledge only after every local representation
     * has a confirmed cache guard. The guard lets a later authoritative response retire the UI overlay while
     * an older request can never roll watched/progress state backwards.
     */
    private suspend fun synchronizeJellyfinWatchStateMutation(
        mutation: JellyfinWatchMutation,
        session: UserSession,
        origin: String,
    ): Boolean {
        if (!materializeJellyfinWatchStateMutation(mutation)) {
            container.logger.event("jellyfin", "$origin remains queued until local watch state can be updated")
            return false
        }
        if (container.authRepository.currentUser.value != session) {
            container.logger.event("jellyfin", "$origin was superseded before it reached the server")
            return false
        }
        val submitted = container.authRepository.runWithActiveSession(session) {
            submitJellyfinWatchStateMutation(mutation)
        }
        if (!submitted) {
            container.logger.event("jellyfin", "$origin was superseded before it reached the server")
            return false
        }
        // A completed request from a prior account must never rewrite the newly selected account's gallery,
        // cache, download metadata, or resume checkpoint. Keep its journal entry for the original account.
        if (container.authRepository.currentUser.value != session) {
            container.logger.event("jellyfin", "$origin completed after the active account changed")
            return false
        }

        // Hold materialization while confirming + acknowledging. Otherwise a cached-session collector could
        // install a newer unconfirmed token between these two operations and leave it pinned forever.
        val reconciliation = watchStateMaterializationMutex.withLock {
            if (deletingAllData) return@withLock null
            val cachePatch = jellyfinWatchStateCachePatches[mutation.operationId] ?: return@withLock null
            val cacheConfirmed = withContext(Dispatchers.IO) {
                container.libraryCache.confirmItemPatch(cachePatch)
            }
            if (!cacheConfirmed) return@withLock null
            val acknowledged = withContext(Dispatchers.IO) {
                container.jellyfinWatchMutationStore.acknowledgeIfCurrent(mutation)
            }
            if (!acknowledged) return@withLock null
            jellyfinWatchStateCachePatches.remove(mutation.operationId)
            JellyfinWatchStateReconciliation(mutation = mutation, session = session, cachePatch = cachePatch)
        }
        if (reconciliation == null) {
            container.logger.event("jellyfin", "$origin remains queued until its cache update can be confirmed")
            return false
        }
        if (container.authRepository.currentUser.value != session) return false

        // A successful mutation proves this session can reach Jellyfin. ONLINE also retries a reconciliation
        // that had to use a cached fallback during a short connection loss.
        pendingWatchStateReconciliations[mutation.operationId] = reconciliation
        container.jellyfinConnectionMonitor.markOnline()
        scheduleJellyfinWatchStateReconciliation(reconciliation)
        // A focused refresh reconciles server-owned season/series counts and resume rows. Its ordered cache
        // write may retire the same local patch before the dedicated item response gets back.
        refreshJellyfinMaterializationsAfterWatchStateMutation(session)
        container.logger.event("jellyfin", "$origin synced for ${mutation.itemId}")
        return true
    }
    private suspend fun submitJellyfinWatchStateMutation(mutation: JellyfinWatchMutation) {
        when (mutation.kind) {
            JellyfinWatchMutationKind.MARK_PLAYED ->
                container.jellyfinClient.markPlayed(mutation.itemId, played = true)
            JellyfinWatchMutationKind.MARK_UNPLAYED ->
                container.jellyfinClient.markPlayed(mutation.itemId, played = false)
            JellyfinWatchMutationKind.RESET_PROGRESS ->
                container.jellyfinClient.resetProgress(mutation.itemId)
        }
    }

    private fun handleJellyfinWatchStateMutationFailure(
        session: UserSession,
        origin: String,
        error: Throwable,
    ) {
        if (container.authRepository.currentUser.value != session) {
            container.logger.event("jellyfin", "$origin ended after the active account changed")
            return
        }
        when {
            isSessionExpired(error) -> handleSessionExpired()
            isJellyfinConnectivityFailure(error) -> {
                container.jellyfinConnectionMonitor.markUnavailable()
                container.logger.event("jellyfin", "$origin remains queued while Jellyfin is unavailable")
            }
            else -> container.logger.w("jellyfin", "$origin failed; its durable intent remains queued", error)
        }
    }

    /**
     * Apply a pending absolute state locally before the remote call. A cache/download failure deliberately
     * leaves the journal entry in place, so replay repairs the incomplete materialization rather than
     * acknowledging an action that could later be contradicted by stale offline data.
     */
    private suspend fun materializeJellyfinWatchStateMutation(mutation: JellyfinWatchMutation): Boolean =
        watchStateMaterializationMutex.withLock {
            if (deletingAllData) return@withLock false
            val transform = mutation.transform()
            val session = mutation.session()
            val mutationKey = mutation.key()
            if (activeJellyfinSession() == session) {
                jellyfinWatchStateRevisions[mutationKey] =
                    (jellyfinWatchStateRevisions[mutationKey] ?: 0L) + 1L
                jellyfinWatchStateOverrides[mutationKey] = JellyfinWatchStateOverride(
                    operationId = mutation.operationId,
                    transform = transform,
                    clearsResume = mutation.clearsResume(),
                )
                applyJellyfinItemMutation(mutation.itemId, transform, clearsResume = mutation.clearsResume())
            }
            try {
                if (mutation.clearsResume()) clearMatchingJellyfinResume(mutation)
                val cachePatch = persistJellyfinItemMutation(
                    mutation.serverId,
                    mutation.userId,
                    mutation.itemId,
                    transform,
                )
                // Delete-all waits for this mutex. Do not retain an in-memory token/overlay after it has
                // announced deletion; the immediately following delete clears the backing stores.
                if (deletingAllData) {
                    if (jellyfinWatchStateOverrides[mutationKey]?.operationId == mutation.operationId) {
                        jellyfinWatchStateOverrides.remove(mutationKey)
                    }
                    return@withLock false
                }
                jellyfinWatchStateCachePatches[mutation.operationId] = cachePatch
                true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                container.logger.w("jellyfin", "Couldn't materialize a pending Jellyfin watch-state action", error)
                false
            }
        }
    /** Materialize cache/download state while offline; only [schedulePendingJellyfinWatchStateReplay] may submit it. */
    private suspend fun materializePendingJellyfinWatchStateMutations(session: UserSession) {
        container.jellyfinWatchMutationStore.pendingFor(session.serverId, session.userId).forEach { mutation ->
            materializeJellyfinWatchStateMutation(mutation)
        }
    }

    /**
     * Fetch one authoritative post-confirmation item. A cached/offline fallback keeps the cache token active;
     * the ONLINE collector retries this once Jellyfin is reachable again. A successful remote response that
     * started after confirmation naturally retires [LibraryCache]'s matching stale-response guard.
     */
    private fun scheduleJellyfinWatchStateReconciliation(
        reconciliation: JellyfinWatchStateReconciliation,
    ) {
        if (deletingAllData || container.authRepository.currentUser.value != reconciliation.session) return
        if (!activeWatchStateReconciliations.add(reconciliation.mutation.operationId)) return
        watchStateMutationScope.launch {
            try {
                if (deletingAllData || container.authRepository.currentUser.value != reconciliation.session) {
                    return@launch
                }
                val result = container.jellyfinMediaSource.item(reconciliation.mutation.itemId)
                val authoritative = result.getOrNull()
                if (authoritative == null) {
                    result.exceptionOrNull()?.let { error ->
                        if (isSessionExpired(error)) handleSessionExpired()
                    }
                    return@launch
                }
                if (deletingAllData || container.authRepository.currentUser.value != reconciliation.session) {
                    return@launch
                }
                val cachePatchStillActive = withContext(Dispatchers.IO) {
                    container.libraryCache.isItemPatchActive(reconciliation.cachePatch)
                }
                if (cachePatchStillActive) return@launch
                retireJellyfinWatchStateReconciliation(reconciliation, authoritative)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                container.logger.w("jellyfin", "Couldn't reconcile a confirmed Jellyfin watch-state action", error)
            } finally {
                activeWatchStateReconciliations.remove(reconciliation.mutation.operationId)
            }
        }
    }

    /** Remove only the matching optimistic UI state; a newer action for this item always wins. */
    private fun retireJellyfinWatchStateReconciliation(
        reconciliation: JellyfinWatchStateReconciliation,
        authoritative: MediaItem,
    ) {
        if (deletingAllData || container.authRepository.currentUser.value != reconciliation.session) return
        val operationId = reconciliation.mutation.operationId
        if (pendingWatchStateReconciliations[operationId] != reconciliation) return
        val mutationKey = reconciliation.mutation.key()
        val override = jellyfinWatchStateOverrides[mutationKey]
        if (override?.operationId != operationId) {
            // The action was superseded (or the visible scope was discarded). It no longer owns any UI state.
            pendingWatchStateReconciliations.remove(operationId)
            return
        }
        pendingWatchStateReconciliations.remove(operationId)
        jellyfinWatchStateOverrides.remove(mutationKey)
        applyAuthoritativeJellyfinItem(authoritative)
    }

    /** Replace the optimistic leaf item everywhere without reviving a reset/watched continue-watching row. */
    private fun applyAuthoritativeJellyfinItem(authoritative: MediaItem) = _state.update { st ->
        fun update(item: MediaItem): MediaItem = if (item.id == authoritative.id) authoritative else item
        fun update(items: List<MediaItem>) = items.map(::update)
        val shouldRemainInContinueWatching = !authoritative.played &&
            (authoritative.resumePositionSeconds ?: 0L) > 0L
        st.copy(
            selectedItem = st.selectedItem?.let(::update),
            nowPlayingItem = st.nowPlayingItem?.let(::update),
            items = update(st.items),
            searchResults = update(st.searchResults),
            libraries = update(st.libraries),
            playlist = st.playlist.copy(
                entries = st.playlist.entries.map { entry -> entry.copy(item = update(entry.item)) },
            ),
            continueWatching = if (shouldRemainInContinueWatching) {
                update(st.continueWatching)
            } else {
                st.continueWatching.filterNot { it.id == authoritative.id }
            },
        )
    }
    private fun JellyfinWatchMutation.key() =
        JellyfinWatchMutationKey(serverId = serverId, userId = userId, itemId = itemId)

    private fun JellyfinWatchMutation.session() = UserSession(userId = userId, serverId = serverId)

    private fun JellyfinWatchMutation.clearsResume(): Boolean =
        kind != JellyfinWatchMutationKind.MARK_UNPLAYED

    private fun JellyfinWatchMutation.transform(): (MediaItem) -> MediaItem = when (kind) {
        JellyfinWatchMutationKind.MARK_PLAYED -> { item -> item.withJellyfinWatchState(played = true) }
        JellyfinWatchMutationKind.MARK_UNPLAYED -> { item -> item.withJellyfinWatchState(played = false) }
        JellyfinWatchMutationKind.RESET_PROGRESS -> { item -> item.withJellyfinProgressReset() }
    }

    private fun setWatchStateMutationUpdating(mutation: JellyfinWatchMutation, updating: Boolean) {
        if (container.authRepository.currentUser.value != mutation.session()) return
        _state.update { current ->
            current.copy(
                watchStateMutationItemIds = if (updating) {
                    current.watchStateMutationItemIds + mutation.itemId
                } else {
                    current.watchStateMutationItemIds - mutation.itemId
                },
            )
        }
    }

    private fun hasPendingJellyfinWatchStateMutation(serverId: String, userId: String, itemId: String): Boolean =
        container.jellyfinWatchMutationStore.pendingFor(serverId, userId, itemId) != null

    private fun isPlaybackBlockedByPendingJellyfinWatchState(item: MediaItem, session: UserSession?): Boolean =
        item.sourceId == MediaSourceIds.JELLYFIN && session != null &&
            hasPendingJellyfinWatchStateMutation(session.serverId, session.userId, item.id)
    private fun isSmartResumeBlockedByWatchMutation(record: SmartResumeRecord): Boolean {
        if (record.sourceType !in setOf(SmartResumeSourceType.JELLYFIN, SmartResumeSourceType.DOWNLOADED)) return false
        val serverId = record.serverId ?: return false
        val userId = record.userId ?: return false
        return container.jellyfinWatchMutationStore.pendingFor(serverId, userId, record.mediaId)
            ?.clearsResume() == true
    }

    private fun isPlaybackActiveFor(itemId: String): Boolean =
        _state.value.playback != null && _state.value.nowPlayingItem?.id == itemId

    /** Immediately reflect a confirmed or queued mutation across each materialized UI list and playback queue. */
    private fun applyJellyfinItemMutation(
        itemId: String,
        transform: (MediaItem) -> MediaItem,
        clearsResume: Boolean,
    ) = _state.update { st ->
        fun update(item: MediaItem): MediaItem = if (item.id == itemId) transform(item) else item
        fun update(list: List<MediaItem>) = list.map(::update)
        st.copy(
            selectedItem = st.selectedItem?.let(::update),
            nowPlayingItem = st.nowPlayingItem?.let(::update),
            items = update(st.items),
            searchResults = update(st.searchResults),
            libraries = update(st.libraries),
            playlist = st.playlist.copy(
                entries = st.playlist.entries.map { entry -> entry.copy(item = update(entry.item)) },
            ),
            continueWatching = if (clearsResume) {
                st.continueWatching.filterNot { it.id == itemId }
            } else {
                update(st.continueWatching)
            },
        )
    }

    /** Apply journal and confirmed local mutations to a list that may have been requested before either completed. */
    private fun withConfirmedJellyfinWatchState(sourceId: String, items: List<MediaItem>): List<MediaItem> {
        if (sourceId != MediaSourceIds.JELLYFIN) return items
        val session = activeJellyfinSession() ?: return items
        val transforms = LinkedHashMap<String, (MediaItem) -> MediaItem>()
        container.jellyfinWatchMutationStore.pendingFor(session.serverId, session.userId).forEach { mutation ->
            transforms[mutation.itemId] = mutation.transform()
        }
        jellyfinWatchStateOverrides.forEach { (key, override) ->
            if (key.serverId == session.serverId && key.userId == session.userId) {
                transforms[key.itemId] = override.transform
            }
        }
        if (transforms.isEmpty()) return items
        return items.map { item -> transforms[item.id]?.invoke(item) ?: item }
    }

    /** A watched/reset action clears the resume row; do not let an older result put it back. */
    private fun withConfirmedJellyfinContinueWatchingState(sourceId: String, items: List<MediaItem>): List<MediaItem> {
        if (sourceId != MediaSourceIds.JELLYFIN) return items
        val session = activeJellyfinSession() ?: return items
        val clearingIds = mutableSetOf<String>()
        container.jellyfinWatchMutationStore.pendingFor(session.serverId, session.userId)
            .filter { it.clearsResume() }
            .mapTo(clearingIds) { it.itemId }
        jellyfinWatchStateOverrides.forEach { (key, override) ->
            if (key.serverId == session.serverId && key.userId == session.userId && override.clearsResume) {
                clearingIds += key.itemId
            }
        }
        return withConfirmedJellyfinWatchState(sourceId, items).filterNot { it.id in clearingIds }
    }

    /** Keep offline browsing/search and the Downloaded shelf aligned with a server-confirmed mutation. */
    private suspend fun persistJellyfinItemMutation(
        serverId: String,
        userId: String,
        itemId: String,
        transform: (MediaItem) -> MediaItem,
    ): LibraryCache.ItemPatchToken = withContext(Dispatchers.IO) {
        val scope = JellyfinLibraryScope(serverId, userId).cacheKey
        // patchItem deliberately throws on a write error. The durable journal must remain pending instead of
        // acknowledging an action that an old offline record could subsequently undo.
        val cachePatch = container.libraryCache.patchItem(scope, itemId, transform)
        val owner = DownloadOwner(serverId, userId)
        val entry = container.downloadStore.get(owner, itemId) ?: return@withContext cachePatch
        val snapshot = entry.mediaItem?.takeIf { it.id == itemId } ?: return@withContext cachePatch
        container.downloadStore.upsert(entry.copy(mediaItem = transform(snapshot)))
        cachePatch
    }
    /** A watched/reset action wins over an older online or downloaded crash checkpoint, never a newer one. */
    private suspend fun clearMatchingJellyfinResume(mutation: JellyfinWatchMutation) = withContext(Dispatchers.IO) {
        val owner = DownloadOwner(mutation.serverId, mutation.userId)
        val resumeKey = DownloadIdentity(owner, mutation.itemId).resumeKey
        container.resumeStore.get(resumeKey)
            ?.takeIf { it.updatedAt <= mutation.createdAtMillis }
            ?.let { container.resumeStore.remove(resumeKey) }
        val record = container.smartResumeStore.current
        if (record != null &&
            record.mediaId == mutation.itemId &&
            record.serverId == mutation.serverId &&
            record.userId == mutation.userId &&
            record.sourceType in setOf(SmartResumeSourceType.JELLYFIN, SmartResumeSourceType.DOWNLOADED) &&
            record.updatedAtMillis <= mutation.createdAtMillis
        ) {
            container.smartResumeStore.clear()
        }
    }
    /** Reconcile server-owned folder counters and resume rows after a confirmed manual leaf mutation. */
    private fun refreshJellyfinMaterializationsAfterWatchStateMutation(session: UserSession) {
        if (container.authRepository.currentUser.value != session) return
        val snapshot = _state.value
        val target = snapshot.galleryTarget()
        if (target.sourceId != MediaSourceIds.JELLYFIN) return
        launchGalleryLoad(target)
        viewModelScope.launch {
            if (container.authRepository.currentUser.value == session) loadContinueWatching(target.sourceId)
        }
        refreshActiveSearchResults(target.sourceId, snapshot.searchQuery, expectedSession = session)
    }
    /** Reconcile server-reported playback progress after a real playback stop. */
    private fun refreshGalleryAfterPlaybackReport() {
        val session = container.authRepository.currentUser.value ?: return
        refreshJellyfinMaterializationsAfterWatchStateMutation(session)
    }

    private fun refreshActiveSearchResults(
        sourceId: String,
        query: String,
        expectedSession: UserSession? = null,
    ) {
        if (query.isBlank()) return
        viewModelScope.launch {
            if (expectedSession != null && container.authRepository.currentUser.value != expectedSession) return@launch
            val result = sourceFor(sourceId).search(query.trim())
            result.onSuccess { results ->
                _state.update { current ->
                    if (current.activeSourceId == sourceId && current.searchQuery == query &&
                        (expectedSession == null || container.authRepository.currentUser.value == expectedSession)
                    ) {
                        current.copy(searchResults = withConfirmedJellyfinWatchState(sourceId, results))
                    } else {
                        current
                    }
                }
            }.onFailure { error ->
                if (_state.value.activeSourceId == sourceId && _state.value.searchQuery == query && isSessionExpired(error)) {
                    handleSessionExpired()
                }
            }
        }
    }

    private fun isJellyfinConnectivityFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is IOException ||
                (cause is JellyfinHttpException && !cause.isUnauthorized && UpstreamRetry.isRetryableStatus(cause.code))
        }

    /** Explicit retry starts a fresh bounded recovery budget but keeps the same physical TV and checkpoint. */
    fun retryPlayback() {
        val ctx = reconnectContext ?: return
        cancelReconnect()
        viewModelScope.launch {
            playbackStarting = true
            _state.update { current ->
                current.copy(
                    route = Route.PLAYBACK,
                    playback = current.playback?.copy(
                        phase = PlaybackPhase.CONNECTING,
                        isTerminal = false,
                        errorMessage = null,
                        reconnecting = false,
                    ),
                    errorMessage = null,
                )
            }
            try {
                playContext(ctx, ctx.endpoint, lastPlaybackPositionSeconds)
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                val reason = LogRedactor.redact(bestFailureReason(error)).take(180)
                container.logger.w("playback", "Explicit playback retry failed", error)
                _state.update { current ->
                    current.copy(
                        route = Route.PLAYBACK,
                        playback = current.playback?.copy(
                            phase = PlaybackPhase.FAILED,
                            isTerminal = true,
                            errorMessage = "Couldn't start playback: $reason",
                        ),
                    )
                }
            } finally {
                playbackStarting = false
            }
        }
    }

    /** User-requested device change leaves recovery intentionally and returns to one physical-TV picker. */
    fun changeTv() = viewModelScope.launch {
        val ctx = reconnectContext
        val replayItem = (ctx as? ReconnectContext.Online)?.item ?: _state.value.nowPlayingItem
        cancelReconnect()
        smartResumeOverrideSeconds = lastPlaybackPositionSeconds
        if (ctx is ReconnectContext.Local && ctx.downloadId != null) {
            _state.update { it.copy(selectedDownloadId = ctx.downloadId) }
        }
        _state.update {
            it.copy(
                // The user may have opened other gallery details while casting. The picker must replay the
                // item that is actually active on the TV, not whichever detail page was opened most recently.
                selectedItem = replayItem ?: it.selectedItem,
                route = Route.TARGET_PICKER,
                playback = null,
                selectedPhysicalTv = null,
                selectedTarget = null,
                errorMessage = null,
            )
        }
        runCatching { container.playbackEngine.stop() }
        scanTargets()
    }

    private fun controllerFor(target: DiscoveredTarget) =
        if (target.protocol == Protocol.CAST) container.castController else container.dlnaController

    private fun smartResumeDeviceContext(
        physicalTv: PhysicalTv,
        endpoint: DiscoveredTarget,
    ): SmartResumeDeviceContext {
        val endpointKey = PhysicalEndpointKey.from(endpoint)
        val hasStablePhysicalIdentity = physicalTv.availableEndpoints.any { PhysicalEndpointKey.from(it) != null }
        return SmartResumeDeviceContext(
            physicalDeviceStableId = physicalTv.id.takeIf { hasStablePhysicalIdentity },
            physicalDeviceReference = physicalTv.displayName,
            lastSuccessfulProtocol = endpoint.protocol,
            stableEndpointIdentity = endpointKey?.storageToken,
        )
    }

    /** Persist only evidence that reached renderer-confirmed PLAYING, never a mere discovery/load. */
    private fun recordSuccessfulPhysicalEndpoint(status: PlaybackStatus) {
        if (status.phase != PlaybackPhase.PLAYING || status.attemptGeneration == lastRecordedPhysicalAttemptGeneration) return
        val context = reconnectContext ?: return
        if (status.protocolName != context.endpoint.protocol.name) return
        val store = container.physicalTvAssociations
        val keys = context.physicalTv.availableEndpoints.mapNotNull(PhysicalEndpointKey::from).distinct()
        val cast = keys.singleOrNull { it.protocol == Protocol.CAST }
        val dlna = keys.singleOrNull { it.protocol == Protocol.DLNA }
        if (cast != null && dlna != null && !store.isBlocked(cast, dlna) && !store.isLinked(cast, dlna)) {
            // A two-endpoint PhysicalTv exists only after the conservative matcher found a mutual,
            // one-to-one confident pair. Renderer-confirmed playback makes that pairing durable.
            store.link(cast, dlna)
        }
        val preferenceScope = (keys + keys.mapNotNull(store::linkedPeer)).distinct()
        if (preferenceScope.isNotEmpty()) {
            store.recordLastSuccessful(preferenceScope, context.endpoint.protocol)
        }
        lastRecordedPhysicalAttemptGeneration = status.attemptGeneration
    }

    private fun ReconnectContext.withEndpoint(endpoint: DiscoveredTarget): ReconnectContext = when (this) {
        is ReconnectContext.Online -> copy(endpoint = endpoint)
        is ReconnectContext.Local -> copy(endpoint = endpoint)
    }

    private fun ReconnectContext.withPhysicalTv(physicalTv: PhysicalTv): ReconnectContext = when (this) {
        is ReconnectContext.Online -> copy(physicalTv = physicalTv)
        is ReconnectContext.Local -> copy(physicalTv = physicalTv)
    }

    private suspend fun playContext(
        context: ReconnectContext,
        endpoint: DiscoveredTarget,
        resumeAtSeconds: Long,
        recoveryContinuation: PlaybackRecoveryContinuation? = null,
    ) {
        val deviceContext = smartResumeDeviceContext(context.physicalTv, endpoint)
        when (context) {
            is ReconnectContext.Online -> {
                requireActiveOnlinePlaybackOwner(context.owner)
                container.playbackEngine.play(
                    context.item,
                    controllerFor(endpoint),
                    endpoint,
                    streamPreferences(context.item),
                    resumePositionOverrideSeconds = resumeAtSeconds,
                    autoAdvance = shouldAutoAdvance(context.item, _state.value.playlist),
                    smartResumeSeed = onlineSmartResumeSeed(context.item, context.owner),
                    smartResumeDeviceContext = deviceContext,
                    recoveryContinuation = recoveryContinuation,
                )
            }
            is ReconnectContext.Local -> container.playbackEngine.playLocal(
                filePath = context.filePath,
                contentType = context.contentType,
                title = context.title,
                runtimeSeconds = context.runtimeSeconds,
                allowClientTranscode = container.playbackPreferences.transcodeLocalOnDevice,
                resumePositionSeconds = resumeAtSeconds,
                smartResumeSeed = context.smartResumeSeed,
                smartResumeDeviceContext = deviceContext,
                target = controllerFor(endpoint),
                selectedTarget = endpoint,
                recoveryContinuation = recoveryContinuation,
            )
        }
    }

    fun stopPlayback() = viewModelScope.launch {
        cancelReconnect()
        onlinePlaybackOwner = null
        reconnectContext = null
        pendingResumeDeviceContext = null
        saveResumeNow()
        runCatching { container.playbackEngine.stop() }
        _state.update { it.copy(route = Route.GALLERY, playback = null, nowPlayingItem = null) }
        // Persist the playback session's events now so they're in a report even if the app is later
        // killed, and refresh the resume row from the server's updated position.
        container.flushDiagnostics()
        // The server now has an updated resume point for what we just watched — reconcile every cached
        // gallery representation rather than only the Continue Watching row.
        refreshGalleryAfterPlaybackReport()
    }

    // ----- auto-reconnect after an unexpected renderer disconnect -----

    /** One same-endpoint retry, then at most one alternate endpoint belonging to the same physical TV. */
    private fun startReconnect() = startBoundedDeviceRecovery(retrySameEndpoint = true)

    /** Internal stream/format recovery is exhausted; only a qualified physical-TV protocol handoff remains. */
    private fun startTerminalProtocolFallback(status: PlaybackStatus) = startBoundedDeviceRecovery(
        retrySameEndpoint = false,
        initialStage = status.attemptHistory.lastOrNull()?.failureStage,
        initialCause = status.attemptHistory.lastOrNull()?.failureCause,
    )

    private fun startBoundedDeviceRecovery(
        retrySameEndpoint: Boolean,
        initialStage: PlaybackFailureStage? = null,
        initialCause: PlaybackFailureCause? = null,
    ) {
        if (!container.permissions.hasLocalNetworkAccess()) {
            onLocalNetworkPermissionDenied()
            return
        }
        val originalContext = reconnectContext ?: return
        if (reconnectJob?.isActive == true) return
        val generation = ++reconnectGeneration
        reconnecting = true
        reconnectJob = viewModelScope.launch {
            var recoveryContext = originalContext
            try {
                _state.update { current ->
                    current.copy(
                        route = Route.PLAYBACK,
                        playback = current.playback?.copy(
                            reconnecting = true,
                            phase = if (retrySameEndpoint) PlaybackPhase.RECONNECTING else PlaybackPhase.CHANGING_PROTOCOL,
                            errorMessage = null,
                            isTerminal = false,
                        ),
                    )
                }
                if (retrySameEndpoint) {
                    container.logger.event("playback", "Connection lost; waiting for the TV to reappear")
                    delay(RECONNECT_DELAY_MS)
                    if (generation != reconnectGeneration) return@launch
                    val rediscovered = if (PhysicalTvReconnectPolicy.hasStableIdentity(
                            originalContext.physicalTv,
                            originalContext.endpoint,
                        )
                    ) {
                        rediscoverPhysicalTvAfterRestart(originalContext, generation)
                    } else {
                        container.logger.w(
                            "playback",
                            "TV endpoint has no stable identity; restart rediscovery cannot safely auto-select it",
                        )
                        null
                    }
                    if (generation != reconnectGeneration) return@launch
                    val sameProtocolEndpoint = if (rediscovered != null) {
                        recoveryContext = originalContext.withPhysicalTv(rediscovered.physicalTv)
                        reconnectContext = recoveryContext
                        rediscovered.sameProtocolEndpoint
                    } else {
                        // Preserve the old one-shot behavior when identity is unavailable or discovery
                        // timed out. It can still work when the reboot retained its route/address.
                        originalContext.endpoint
                    }
                    if (sameProtocolEndpoint != null) {
                        recoveryContext = recoveryContext.withEndpoint(sameProtocolEndpoint)
                        reconnectContext = recoveryContext
                        _state.update { current ->
                            current.copy(
                                selectedPhysicalTv = recoveryContext.physicalTv,
                                selectedTarget = sameProtocolEndpoint,
                            )
                        }
                    }
                    if (sameProtocolEndpoint != null &&
                        container.playbackEngine.retrySameEndpointAfterDisconnect(sameProtocolEndpoint)
                    ) {
                        container.logger.event("playback", "Reconnected and resumed on the same endpoint")
                        return@launch
                    }
                }
                if (generation != reconnectGeneration) return@launch
                val latest = container.playbackEngine.recoverySnapshot().attempts.lastOrNull()
                val switched = tryAlternateProtocol(
                    recoveryContext,
                    failureStage = latest?.failureStage ?: initialStage ?: PlaybackFailureStage.UNKNOWN,
                    failureCause = latest?.failureCause ?: initialCause ?: PlaybackFailureCause.UNKNOWN,
                    generation = generation,
                )
                if (generation != reconnectGeneration) return@launch
                if (switched) {
                    container.logger.event("playback", "Playback resumed through the TV's alternate protocol")
                    return@launch
                }
                surfaceTerminalPlaybackFailure(
                    recoveryContext,
                    if (retrySameEndpoint) {
                        "The connection to the TV couldn't be restored automatically."
                    } else {
                        "Stream Ferry tried the available compatible playback options."
                    },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                if (generation != reconnectGeneration) return@launch
                container.logger.w("playback", "Automatic playback recovery failed", error)
                surfaceTerminalPlaybackFailure(recoveryContext, "Playback couldn't be restored automatically.")
            } finally {
                // A reboot scan keeps the Cast route callback alive until connect() can select the
                // refreshed route. Release it only after the whole recovery attempt completes.
                withContext(NonCancellable) {
                    runCatching { container.castController.stopDiscovery() }
                        .onFailure { container.logger.w("discovery", "Couldn't stop restart rediscovery", it) }
                }
                if (generation == reconnectGeneration) {
                    reconnecting = false
                    reconnectJob = null
                    _state.update { current ->
                        current.copy(playback = current.playback?.copy(reconnecting = false))
                    }
                }
            }
        }
    }

    private suspend fun rediscoverPhysicalTvAfterRestart(
        context: ReconnectContext,
        generation: Long,
    ): PhysicalTvReconnectMatch? {
        val deadlineNanos = System.nanoTime() +
            PhysicalTvReconnectPolicy.REDISCOVERY_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        var completedScans = 0
        while (currentCoroutineContext().isActive && generation == reconnectGeneration) {
            val remainingMillis = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(0)
            if (remainingMillis == 0L) break
            val discovered = discoverTargetsDuringReconnect(remainingMillis)
            if (generation != reconnectGeneration) return null
            val physicalTvs = PhysicalTvAggregator.aggregate(
                discovered,
                container.physicalTvAssociations,
            ).physicalTvs
            val match = PhysicalTvReconnectPolicy.find(
                physicalTvs,
                previousTv = context.physicalTv,
                previousEndpoint = context.endpoint,
            )
            if (match != null) {
                val cast = discovered.filter { it.protocol == Protocol.CAST }
                val dlna = discovered.filter { it.protocol == Protocol.DLNA }
                _state.update {
                    it.copy(
                        castTargets = cast,
                        dlnaTargets = dlna,
                        physicalTvs = physicalTvs,
                        selectedPhysicalTv = match.physicalTv,
                        selectedTarget = match.sameProtocolEndpoint ?: match.physicalTv.selectEndpoint(),
                    )
                }
                container.logger.event(
                    "playback",
                    "TV rediscovered after restart (same protocol available=${match.sameProtocolEndpoint != null})",
                )
                return match
            }
            completedScans += 1
            val afterScanRemaining = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(0)
            if (afterScanRemaining == 0L) break
            val retryDelay = PhysicalTvReconnectPolicy.delayAfterScanMillis(completedScans)
                .coerceAtMost(afterScanRemaining)
            container.logger.event(
                "playback",
                "TV has not reappeared yet (rediscovery scan $completedScans); retrying",
            )
            delay(retryDelay)
        }
        container.logger.w("playback", "TV restart rediscovery timed out")
        return null
    }

    private suspend fun discoverTargetsDuringReconnect(remainingMillis: Long): List<DiscoveredTarget> =
        coroutineScope {
            val castTimeout = remainingMillis.coerceAtMost(CAST_SCAN_MS)
            val dlnaTimeout = remainingMillis.coerceAtMost(DLNA_SCAN_MS)
            val castDeferred = async {
                try {
                    if (container.awaitCastContext() == null) {
                        Result.success(emptyList())
                    } else {
                        Result.success(container.castController.discover(castTimeout))
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val dlnaDeferred = async {
                try {
                    Result.success(container.dlnaController.discover(dlnaTimeout))
                } catch (c: CancellationException) {
                    throw c
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val castResult = castDeferred.await()
            val dlnaResult = dlnaDeferred.await()
            castResult.exceptionOrNull()?.let {
                container.logger.w("discovery", "Cast restart rediscovery failed", it)
            }
            dlnaResult.exceptionOrNull()?.let {
                container.logger.w("discovery", "DLNA restart rediscovery failed", it)
            }
            castResult.getOrDefault(emptyList()) + dlnaResult.getOrDefault(emptyList())
        }

    private suspend fun tryAlternateProtocol(
        context: ReconnectContext,
        failureStage: PlaybackFailureStage,
        failureCause: PlaybackFailureCause,
        generation: Long,
    ): Boolean {
        val alternate = when (context.endpoint.protocol) {
            Protocol.CAST -> context.physicalTv.dlnaEndpoint
            Protocol.DLNA -> context.physicalTv.castEndpoint
        } ?: return false
        val continuation = container.playbackEngine.reserveAlternateProtocolContinuation(
            hasAlternateProtocol = true,
            failureStage = failureStage,
            failureCause = failureCause,
            sameEndpointRecoveryExhausted = true,
            isLocalSessionHint = context is ReconnectContext.Local,
            isOnlineSessionHint = context is ReconnectContext.Online,
        ) ?: return false
        if (generation != reconnectGeneration) return false
        val alternateContext = context.withEndpoint(alternate)
        reconnectContext = alternateContext
        _state.update { current ->
            current.copy(
                selectedTarget = alternate,
                playback = current.playback?.copy(
                    protocol = alternate.protocol.name,
                    phase = PlaybackPhase.CHANGING_PROTOCOL,
                    adaptiveNote = "Trying another connection to the same TV…",
                    reconnecting = true,
                ),
            )
        }
        return try {
            playContext(alternateContext, alternate, lastPlaybackPositionSeconds, continuation)
            if (generation != reconnectGeneration) return false
            _state.update {
                it.copy(
                    selectedPhysicalTv = alternateContext.physicalTv,
                    selectedTarget = alternate,
                )
            }
            true
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            container.logger.w("playback", "Alternate TV protocol failed", error)
            false
        }
    }

    /** Release renderer/proxy resources while retaining a stable, actionable Now Playing error card. */
    private suspend fun surfaceTerminalPlaybackFailure(context: ReconnectContext, message: String) {
        val snapshot = container.playbackEngine.recoverySnapshot()
        try {
            container.playbackEngine.stop()
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            container.logger.w("playback", "Terminal playback cleanup failed", error)
        }
        _state.update { current ->
            val prior = current.playback ?: PlaybackUiState(
                targetName = context.physicalTv.displayName,
                protocol = context.endpoint.protocol.name,
                durationSeconds = currentDurationSeconds,
                volumeSupported = false,
            )
            current.copy(
                route = Route.PLAYBACK,
                playback = prior.copy(
                    phase = PlaybackPhase.FAILED,
                    isTerminal = true,
                    reconnecting = false,
                    errorMessage = message,
                    attemptGeneration = snapshot.generation,
                    attemptHistory = snapshot.attempts,
                    recoveryBudget = snapshot.budgetStatus,
                ),
                errorMessage = null,
            )
        }
    }

    private fun cancelReconnect() {
        reconnectGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
    }


    /** Ends active Cast route scanning when the picker is no longer able to use its result. */
    private fun stopTargetDiscovery() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value.isScanningTargets) _state.update { it.copy(isScanningTargets = false) }
        viewModelScope.launch {
            try {
                container.castController.stopDiscovery()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                container.logger.w("discovery", "Couldn't stop Cast discovery", e)
            }
        }
    }
    /**
     * Use the local AtomicFile checkpoint only for the exact Jellyfin identity currently selected.
     * This lets a direct tap on Continue Watching recover from a crash even before the server receives
     * the next progress report, without allowing another account's checkpoint to affect playback.
     */
    private fun durableOnlineResumePosition(item: MediaItem, owner: UserSession): Long? =
        if (hasPendingJellyfinWatchStateMutation(owner.serverId, owner.userId, item.id)) {
            null
        } else {
            SmartResumePositionReconciler.reconcileJellyfinItem(
                record = container.smartResumeStore.current,
                itemId = item.id,
                serverId = owner.serverId,
                userId = owner.userId,
                jellyfinResumeSeconds = item.resumePositionSeconds,
            )
        }

    /** Throttled persist of the current local-file playback position ("where you left off"). */
    private fun saveResumeThrottled(positionSeconds: Long) {
        val key = currentResumeKey ?: return
        val now = System.currentTimeMillis()
        if (now - lastResumeSaveMs < RESUME_SAVE_INTERVAL_MS) return
        lastResumeSaveMs = now
        container.resumeStore.save(key, positionSeconds, currentDurationSeconds)
    }

    /** Persist the latest position immediately (on stop), then clear the session resume key. */
    private fun saveResumeNow() {
        currentResumeKey?.let { container.resumeStore.save(it, lastPlaybackPositionSeconds, currentDurationSeconds) }
        currentResumeKey = null
        currentDurationSeconds = null
        lastResumeSaveMs = 0L
    }

    /** A genuine local/download completion is not resumable; discard any checkpoint from its last status tick. */
    private fun clearCompletedLocalResume() {
        currentResumeKey?.let { container.resumeStore.remove(it) }
        currentResumeKey = null
        currentDurationSeconds = null
        lastResumeSaveMs = 0L
    }

    /**
     * In-app poster URL for [item] sized to ~[targetWidthPx], or null if the item has no art / no server.
     * The token is sent by the image loader as a header, never in this URL; never given to the TV.
     */
    fun posterUrl(item: MediaItem, targetWidthPx: Int): String? =
        if (item.imageTag == null) null else container.jellyfinClient.posterUrl(item.id, item.imageTag, targetWidthPx)

    /**
     * In-app **chapter** thumbnail URL for [item]'s chapter at [chapterIndex] (the seek-preview scrubber),
     * or null if that chapter has no image / no server. Token goes via a header, never the URL or the TV.
     */
    fun chapterImageUrl(item: MediaItem, chapterIndex: Int, targetWidthPx: Int): String? {
        val chapter = item.chapters.getOrNull(chapterIndex) ?: return null
        if (!chapter.hasImage) return null
        return container.jellyfinClient.chapterImageUrl(item.id, chapterIndex, chapter.imageTag, targetWidthPx)
    }

    /** "Prefer original quality (direct play)" setting: Cast tries the original first, transcode fallback. */
    val preferDirectPlay: Boolean get() = container.playbackPreferences.preferDirectPlay
    fun setPreferDirectPlay(value: Boolean) { container.playbackPreferences.preferDirectPlay = value }

    fun setThemeMode(value: ThemeMode) {
        container.appearancePreferences.themeMode = value
        _state.update { it.copy(themeMode = value) }
    }

    /** "Transcode local videos on this device" setting: off (default) => always direct-play local files. */
    val transcodeLocalOnDevice: Boolean get() = container.playbackPreferences.transcodeLocalOnDevice
    fun setTranscodeLocalOnDevice(value: Boolean) { container.playbackPreferences.transcodeLocalOnDevice = value }

    /** "Maximum resolution" setting (video height in px: 2160/1080/720/480). 2160 also enables 4K HDR. */
    val maxVideoHeight: Int get() = container.playbackPreferences.maxVideoHeight
    fun setMaxVideoHeight(value: Int) { container.playbackPreferences.maxVideoHeight = value }

    /** "Autoplay next episode" setting: play the next episode automatically when one finishes. */
    val autoPlayNextEpisode: Boolean get() = container.playbackPreferences.autoPlayNextEpisode
    fun setAutoPlayNextEpisode(value: Boolean) { container.playbackPreferences.autoPlayNextEpisode = value }

    /** "Auto-skip intro/recap" setting: seek past intro/outro/recap segments automatically. */
    val autoSkipSegments: Boolean get() = container.playbackPreferences.autoSkipSegments
    fun setAutoSkipSegments(value: Boolean) { container.playbackPreferences.autoSkipSegments = value }

    /** Preferred audio language (ISO code, "" = none). Auto-selected at play unless a show overrides it. */
    val preferredAudioLanguage: String get() = container.playbackPreferences.preferredAudioLanguage
    fun setPreferredAudioLanguage(code: String) { container.playbackPreferences.preferredAudioLanguage = code }

    /** Keep an online renderer alive at end-of-media only when a real next item can reuse it. */
    private fun shouldAutoAdvance(item: MediaItem?, playlist: PlaybackQueue): Boolean =
        item?.sourceId == MediaSourceIds.JELLYFIN && (
            playlist.isNotEmpty ||
                (container.playbackPreferences.autoPlayNextEpisode &&
                    item.type.equals("Episode", ignoreCase = true) && item.seriesId != null)
            )

    /** Push playlist changes down to an already-playing online renderer. */
    private fun refreshAutoAdvancePolicy() {
        val snapshot = _state.value
        val shouldHold = shouldAutoAdvance(snapshot.nowPlayingItem, snapshot.playlist)
        viewModelScope.launch {
            runCatching { container.playbackEngine.setAutoAdvance(shouldHold) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    container.logger.w("playlist", "Couldn't update next-item hand-off policy", error)
                }
        }
    }

    /** Preferred subtitle language (ISO code, "" = none/off). Auto-enabled at play unless a show overrides it. */
    val preferredSubtitleLanguage: String get() = container.playbackPreferences.preferredSubtitleLanguage
    fun setPreferredSubtitleLanguage(code: String) { container.playbackPreferences.preferredSubtitleLanguage = code }

    /**
     * The per-show language-memory key: the series id for a Jellyfin episode (so all episodes share it),
     * otherwise the item id. Null for on-device local items (no server tracks) so nothing is remembered.
     */
    private fun showLanguageKey(item: MediaItem?): String? {
        if (item == null || item.sourceId == MediaSourceIds.LOCAL) return null
        return item.seriesId ?: item.id
    }

    /** Forget learned format results and physical-TV associations without disturbing active playback. */
    fun resetLearnedTvCapabilities() {
        container.rendererCapabilityStore.clear()
        container.physicalTvAssociations.clear()
        _state.update { current ->
            current.copy(
                physicalTvs = PhysicalTvAggregator.aggregate(
                    current.castTargets + current.dlnaTargets,
                    container.physicalTvAssociations,
                ).physicalTvs,
            )
        }
        container.logger.event("playback", "Learned TV data reset; formats and endpoint associations will be rediscovered")
    }

    /** Build the per-play stream preferences from the persisted playback settings. */
    private fun streamPreferences(item: MediaItem? = _state.value.nowPlayingItem): StreamPreferences {
        // Resolve the desired languages for THIS item: a per-show memory (last used for the series/movie)
        // wins over the global preferred language; with neither, the server default / no-subtitles apply.
        val showKey = showLanguageKey(item)
        val rememberedAudio = showKey?.let { container.showLanguageStore.audioLanguage(it) }
        val rememberedSubtitle = showKey?.let { container.showLanguageStore.subtitle(it) } ?: SubtitleMemory.None
        val audioLanguage = LanguagePreferenceResolver.resolveAudio(
            rememberedAudio, container.playbackPreferences.preferredAudioLanguage,
        )
        val subtitleLanguage = LanguagePreferenceResolver.resolveSubtitle(
            rememberedSubtitle, container.playbackPreferences.preferredSubtitleLanguage,
        )
        return StreamPreferences(
            preferDirectPlay = container.playbackPreferences.preferDirectPlay,
            maxVideoHeight = container.playbackPreferences.maxVideoHeight,
            autoSkipSegments = container.playbackPreferences.autoSkipSegments,
            preferredAudioLanguage = audioLanguage,
            preferredSubtitleLanguage = subtitleLanguage,
        )
    }

    // ----- permissions / diagnostics / data -----

    fun onLocalNetworkPermissionResult(granted: Boolean) {
        val wasGranted = _state.value.localNetworkPermissionGranted
        _state.update { it.copy(localNetworkPermissionGranted = granted) }
        // A settings-driven revocation must be a real teardown, not an empty device list or a background
        // retry. Keep a non-playback route in place unless the revoked permission was actively in use.
        if (wasGranted && !granted) {
            revokeLocalNetworkAccess(moveToTargetPicker = _state.value.route == Route.PLAYBACK)
        }
    }

    /** Called after a user denies the playback request, so the picker can explain the next safe action. */
    fun onLocalNetworkPermissionDenied() = revokeLocalNetworkAccess(moveToTargetPicker = true)

    /** Lets the picker send the user to the app-scoped system permission page without exposing Context to UI. */
    fun localNetworkPermissionSettingsIntent(): Intent = container.permissions.appDetailsSettingsIntent()

    private fun revokeLocalNetworkAccess(moveToTargetPicker: Boolean) {
        stopTargetDiscovery()
        cancelReconnect()
        onlinePlaybackOwner = null
        reconnectContext = null
        pendingResumeDeviceContext = null
        saveResumeNow()
        _state.update { current ->
            current.copy(
                route = if (moveToTargetPicker) Route.TARGET_PICKER else current.route,
                localNetworkPermissionGranted = false,
                castTargets = emptyList(),
                dlnaTargets = emptyList(),
                physicalTvs = emptyList(),
                selectedPhysicalTv = null,
                selectedTarget = null,
                playback = null,
                nowPlayingItem = null,
                isScanningTargets = false,
                errorMessage = "Local-network access is required to find a TV or stream to it. Enable it in App settings and try again.",
            )
        }
        viewModelScope.launch {
            try {
                // PlaybackEngine.stop revokes the proxy session and releases foreground-service locks.
                container.playbackEngine.stop()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                container.logger.w("permission", "Couldn't stop playback after local-network access was lost", e)
            }
        }
    }

    fun refreshDiagnostics() = viewModelScope.launch {
        val snapshot = withContext(Dispatchers.IO) {
            val lanIp = container.networkInfo.lanIpv4()
            val proxyAddr = container.proxyServer.boundAddressRedacted()
            val playbackStatus = container.playbackEngine.status.value
            val nowPlaying = playbackStatus?.let { s ->
                NowPlayingDiagnostics(
                    target = s.targetName,
                    protocol = s.protocolName,
                    mode = s.streamMode,
                    bitrateKbps = s.currentBitrateBps / 1000,
                    throughputKbps = s.measuredThroughputBps / 1000,
                    positionLabel = formatPosition(s.positionSeconds, s.durationSeconds),
                    buffering = s.isBuffering,
                )
            }
            DiagnosticsUiState(
                sdkInfo = "minSdk 34 / target 37",
                localNetworkPermission = container.permissions.localNetworkStatus(),
                googlePlayServices = if (container.castAvailable) "available" else "unavailable",
                backgroundUnrestricted = container.permissions.isBatteryOptimizationExempt(),
                vpnActive = container.networkInfo.isVpnActive(),
                lanIpRedacted = lanIp?.let { ip -> LogRedactor.redactUrl("http://$ip") } ?: "none",
                proxyAddressRedacted = proxyAddr?.let { addr -> LogRedactor.redactUrl("http://$addr") } ?: "not running",
                redactedLog = container.logger.exportRedacted(),
                crashCount = container.crashReporter.countForCurrentBuild(),
                latestCrash = container.crashReporter.latestReportForCurrentBuild(),
                tvTracingEnabled = container.diagnosticsPreferences.tvTracingEnabled,
                appVersion = container.appVersionName(),
                buildType = if (BuildConfig.DEBUG) "debug" else "release",
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidVersion = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                wifiConnected = container.networkInfo.isWifiConnected(),
                wifiNetworkAvailable = container.networkInfo.wifiNetwork() != null,
                notificationsGranted = container.permissions.hasNotificationsPermission(),
                nowPlaying = nowPlaying,
                entries = container.logger.entries(),
            )
        }
        _state.update { it.copy(diagnostics = snapshot) }
    }

    /** Enable/disable opt-in detailed TV-communication tracing (persisted, redacted, local only). */
    fun setTvTracingEnabled(enabled: Boolean) {
        container.setTvTracingEnabled(enabled)
        refreshDiagnostics()
    }

    /** The full redacted diagnostics log + a device/app context header, for share/save. */
    fun diagnosticsForExport(): String {
        val d = _state.value.diagnostics
        return buildString {
            appendLine("=== Stream Ferry diagnostics report ===")
            appendLine("time:              ${java.time.OffsetDateTime.now()}")
            appendLine("app:               ${d.appVersion} (${container.appVersionCode()}) [${d.buildType}]")
            appendLine("device:            ${d.deviceModel}")
            appendLine("android:           ${d.androidVersion}")
            appendLine("sdk:               ${d.sdkInfo}")
            appendLine()
            appendLine("--- network ---")
            appendLine("vpn active:        ${d.vpnActive}")
            appendLine("wifi connected:    ${d.wifiConnected}")
            appendLine("wifi available:    ${d.wifiNetworkAvailable}")
            appendLine("lan ip:            ${d.lanIpRedacted}")
            appendLine("proxy:             ${d.proxyAddressRedacted.ifBlank { "not running" }}")
            appendLine()
            appendLine("--- services ---")
            appendLine("cast (gms):        ${d.googlePlayServices}")
            appendLine("local-net perm:    ${d.localNetworkPermission}")
            appendLine("notifications:     ${if (d.notificationsGranted) "granted" else "denied"}")
            appendLine("background:        ${if (d.backgroundUnrestricted) "unrestricted" else "battery-optimized (may stall screen-off)"}")
            appendLine("tv tracing:        ${d.tvTracingEnabled}")
            d.nowPlaying?.let { np ->
                appendLine()
                appendLine("--- now playing ---")
                appendLine("target:            ${np.target}")
                appendLine("protocol:          ${np.protocol}")
                appendLine("mode:              ${np.mode}")
                appendLine("bitrate:           ${np.bitrateKbps} kbps")
                appendLine("throughput:        ${np.throughputKbps} kbps")
                appendLine("position:          ${np.positionLabel}")
                appendLine("buffering:         ${np.buffering}")
            }
            appendLine()
            appendLine("--- recent events (redacted) ---")
            // Persisted, current-build events — survives app restarts, so a report shared after the
            // playbacks the user wants to show includes them (the in-memory ring is wiped on relaunch).
            val log = container.diagnosticsEventLog.exportForCurrentBuild()
            if (log.isEmpty()) appendLine("(no entries)") else log.forEach { appendLine(it) }
            if (d.crashCount > 0) {
                appendLine()
                appendLine("--- crash reports (latest build only, redacted) ---")
                appendLine(runCatching { container.crashReporter.combinedReportForCurrentBuild() }.getOrDefault("(failed to read crash reports)"))
            }
        }
    }

    /** Combined redacted crash reports from the LATEST build for the share sheet (empty when none). */
    fun crashReportsForExport(): String = runCatching { container.crashReporter.combinedReportForCurrentBuild() }.getOrDefault("")

    /** Dismiss the startup "crash detected" prompt for this session (reports stay in Diagnostics). */
    fun dismissCrashAlert() = _state.update { it.copy(crashAlertCount = null) }

    fun clearCrashLogs() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { container.crashReporter.clear() } }
        _state.update { it.copy(crashAlertCount = null) }
        refreshDiagnostics()
    }

    fun deleteAllData() {
        cancelQuickConnect()
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            deletingAllData = true
            try {
                // The journal/materialization code owns app-private cache and download metadata. Join it before
                // deleting data so a late IO completion cannot recreate a supposedly erased watch-state intent.
                cancelWatchStateMutationsForSessionTransition()
                watchStateMaterializationMutex.withLock { container.deleteAllData() }
                _state.value = AppUiState() // reset to a clean welcome state
            } catch (c: CancellationException) {
                throw c
            } catch (e: SecureStorageException) {
                container.logger.e("security", "Couldn't clear secure app data", e)
                _state.update { it.copy(isBusy = false, loggedIn = false, errorMessage = SECURE_STORAGE_ERROR) }
            } finally {
                deletingAllData = false
            }
        }
    }

    private fun PlaybackStatus.toUi() = PlaybackUiState(
        targetName = targetName,
        protocol = protocolName,
        mediaTitle = title,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        streamMode = streamMode,
        currentBitrateBps = currentBitrateBps,
        measuredThroughputBps = measuredThroughputBps,
        adaptiveNote = adaptiveNote,
        availableBitratesBps = availableBitratesBps,
        isManualQuality = isManualQuality,
        availableVideoCodecs = availableVideoCodecs,
        preferredVideoCodec = preferredVideoCodec,
        automaticMaxVideoHeight = automaticMaxVideoHeight,
        maxVideoHeight = maxVideoHeight,
        isManualMaxVideoHeight = isManualMaxVideoHeight,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoBitrateBps = videoBitrateBps,
        sourceFormat = sourceFormat,
        outputFormat = outputFormat,
        volume = volume,
        volumeSupported = volumeSupported,
        errorMessage = errorMessage,
        phase = phase,
        attemptGeneration = attemptGeneration,
        attemptHistory = attemptHistory,
        recoveryBudget = recoveryBudget,
        isTerminal = isTerminal,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        currentAudioIndex = currentAudioIndex,
        currentSubtitleIndex = currentSubtitleIndex,
        skipSegmentLabel = skipSegment?.label,
    )

    private fun formatPosition(positionSeconds: Long, durationSeconds: Long?): String {
        val pos = formatSeconds(positionSeconds)
        return if (durationSeconds != null) "$pos / ${formatSeconds(durationSeconds)}" else pos
    }

    private fun formatSeconds(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val STATE_ROUTE = "navigation.route"
        const val STATE_DOWNLOADS_ORIGIN = "navigation.downloads_origin"
        const val STATE_SOURCE = "gallery.source"
        const val STATE_SEARCH_QUERY = "gallery.search_query"
        const val CAST_SCAN_MS = 4_000L
        const val DLNA_SCAN_MS = 6_000L
        const val QUICK_CONNECT_POLL_MS = 3_000L
        const val LIBRARY_ERROR = "Couldn't load your library. Check the connection and try again."
        const val SECURE_STORAGE_ERROR =
            "Couldn't update secure storage. Check available device storage, restart the app, and try again."
        const val SEARCH_DEBOUNCE_MS = 350L
        const val RECONNECT_DELAY_MS = 2_000L
        const val RESUME_SAVE_INTERVAL_MS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
