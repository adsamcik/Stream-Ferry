package com.adsamcik.streamferry.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.app.AppContainer
import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.language.LanguagePreferenceResolver
import com.adsamcik.streamferry.core.language.SubtitleMemory
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.resume.SmartResumeDeviceContext
import com.adsamcik.streamferry.core.resume.SmartResumePositionReconciler
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.data.download.DownloadFormat
import com.adsamcik.streamferry.data.download.MediaDownloader.DownloadState
import com.adsamcik.streamferry.data.jellyfin.HttpApprovalRequiredException
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.data.jellyfin.QuickConnectSession
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.permissions.AndroidNetworkPermissionManager
import com.adsamcik.streamferry.physical.PhysicalEndpointKey
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.physical.PhysicalTvAggregator
import com.adsamcik.streamferry.physical.PhysicalTvResumeMatcher
import com.adsamcik.streamferry.playback.PlaybackStatus
import com.adsamcik.streamferry.playback.PlaybackFailureCause
import com.adsamcik.streamferry.playback.PlaybackFailureStage
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackPreparationException
import com.adsamcik.streamferry.playback.PlaybackRecoveryContinuation
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.ConnectionState
import com.adsamcik.streamferry.ui.state.DiagnosticsUiState
import com.adsamcik.streamferry.ui.state.DownloadUiItem
import com.adsamcik.streamferry.ui.state.NowPlayingDiagnostics
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.state.QuickConnectUiState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.state.toUiState
import com.adsamcik.streamferry.ui.theme.ThemeMode
import com.adsamcik.streamferry.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

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
    private val downloadTitles = HashMap<String, String>()

    // ----- auto-reconnect after an unexpected renderer disconnect -----
    private var reconnectJob: Job? = null
    private var reconnectGeneration = 0L
    // The in-flight device scan, so a play tap can cancel it (its progress spinner is an infinite Compose
    // animation that would otherwise keep posting main-thread frames during the FGS start window).
    private var scanJob: Job? = null
    @Volatile private var reconnecting = false
    @Volatile private var playbackStarting = false
    // True while we're auto-advancing to the next episode (end-of-media -> resolve next -> play). Guards
    // the status->null "ended" branch so it doesn't bounce to the gallery during the hand-off.
    @Volatile private var autoAdvancing = false
    private var lastPlaybackPositionSeconds: Long = 0L
    // What to re-play on reconnect (Jellyfin online OR an on-device / downloaded local file). Set after a
    // successful play()/playLocal(), cleared on stop / a new play.
    private var reconnectContext: ReconnectContext? = null
    private var pendingResumeDeviceContext: SmartResumeDeviceContext? = null
    private var lastRecordedPhysicalAttemptGeneration = -1L

    private sealed interface ReconnectContext {
        val physicalTv: PhysicalTv
        val endpoint: DiscoveredTarget
        data class Online(
            val item: MediaItem,
            override val physicalTv: PhysicalTv,
            override val endpoint: DiscoveredTarget,
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

    // The current session's resume key (a content:// URI, or "dl:<id>") + duration, used to remember
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
                    // While reconnecting OR auto-advancing to the next episode, a transient null status
                    // must NOT bounce the user to the gallery — keep the playback screen for the hand-off.
                    val ended = status == null && cur.route == Route.PLAYBACK &&
                        !playbackStarting && !reconnecting && !autoAdvancing
                    val ui = status?.toUi()?.copy(reconnecting = reconnecting)
                        ?: cur.playback?.takeIf { playbackStarting || reconnecting }
                            ?.copy(reconnecting = reconnecting)
                    cur.copy(
                        playback = ui,
                        route = if (ended) Route.GALLERY else cur.route,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.authRepository.currentUser.collect { user ->
                _state.update { it.copy(loggedIn = user != null, smartResume = container.smartResumeStore.current?.toUiState(user)) }
            }
        }
        viewModelScope.launch {
            container.smartResumeStore.record.collect { record ->
                _state.update { it.copy(smartResume = record?.toUiState(container.authRepository.currentUser.value)) }
            }
        }
        // Autoplay the next episode when one finishes (Netflix-style). end-of-media fires only on a genuine
        // finish (not a user stop); if enabled and the finished item is a series episode with a next one,
        // play it on the same TV.
        viewModelScope.launch {
            container.playbackEngine.endOfMedia.collect { onEndOfMedia() }
        }
        // Best-effort: resume a previously saved server + login across launches.
        viewModelScope.launch {
            val restored = runCatching { container.authRepository.restoreSession() }.getOrNull()
            if (restored != null) {
                _state.update { it.copy(loggedIn = true, connectionState = ConnectionState.CONNECTED) }
            }
            val shouldRestoreLibrary = _state.value.route == Route.GALLERY &&
                (restored != null || _state.value.activeSourceId == MediaSourceIds.LOCAL)
            if (shouldRestoreLibrary) {
                val restoredQuery = _state.value.searchQuery
                _state.update { it.copy(galleryLoading = true) }
                loadRoots()
                if (restoredQuery.isNotBlank()) onSearchQueryChanged(restoredQuery)
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
        // If a download was interrupted by the OS killing the backgrounded process, start the foreground
        // service on launch so it resumes the persisted download(s) from their `.part` files. Guarded:
        // a cold-start foreground-service start can be rejected by the OS, and that must not crash.
        viewModelScope.launch {
            runCatching {
                if (container.downloader.hasPendingPersisted()) container.startDownloadService()
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

    private suspend fun refreshDownloadEntries() {
        downloadEntries = runCatching { container.downloadStore.list() }.getOrDefault(emptyList())
        downloadEntries.forEach { downloadTitles[it.itemId] = it.title }
        _state.update { it.copy(downloads = mergeDownloads(container.downloader.states.value)) }
    }

    private fun mergeDownloads(states: Map<String, DownloadState>): List<DownloadUiItem> {
        val byId = LinkedHashMap<String, DownloadUiItem>()
        downloadEntries.forEach { e ->
            byId[e.itemId] = DownloadUiItem(e.itemId, e.title, "Downloaded", 1f, completed = true)
        }
        states.forEach { (id, st) ->
            val title = downloadTitles[id] ?: container.downloader.titleFor(id) ?: id
            val ui = when (st) {
                is DownloadState.Queued -> DownloadUiItem(id, title, "Queued…", null, completed = false)
                is DownloadState.Running -> DownloadUiItem(id, title, runningText(st), st.fraction, completed = false)
                is DownloadState.Completed -> byId[id] ?: DownloadUiItem(id, title, "Downloaded", 1f, completed = true)
                is DownloadState.Failed -> DownloadUiItem(id, title, st.reason, null, completed = false, failed = true)
            }
            if (st !is DownloadState.Completed || byId[id] == null) byId[id] = ui
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
                loadRoots()
                if (restoredQuery.isNotBlank()) onSearchQueryChanged(restoredQuery)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun onWelcomeContinue() {
        if (_state.value.loggedIn) openLibraries() else navigate(Route.SERVER_SETUP)
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
            val session = container.authRepository.switchServer(serverId)
            if (session != null) openLibraries()
            else _state.update { it.copy(route = Route.SERVER_SETUP, loggedIn = false, errorMessage = "Sign in to that server to continue.") }
        }
    }

    fun forgetServer(serverId: String) {
        cancelQuickConnect()
        viewModelScope.launch {
            container.authRepository.deleteServerProfile(serverId)
            _state.update { it.copy(servers = container.authRepository.servers()) }
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
                        _state.update {
                            it.copy(
                                connectionState = ConnectionState.FAILED,
                                isBusy = false,
                                errorMessage = e.message ?: "Couldn't connect to that server.",
                            )
                        }
                    }
                },
            )
        }
    }

    // ----- auth -----

    fun login(username: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(isBusy = true, errorMessage = null) }
        container.authRepository.login(username, password).fold(
            onSuccess = {
                _state.update { it.copy(isBusy = false, loggedIn = true) }
                openLibraries()
            },
            onFailure = {
                _state.update { it.copy(isBusy = false, errorMessage = "Login failed. Check your username and password.") }
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
            container.authRepository.completeQuickConnect(session).fold(
                onSuccess = {
                    _state.update { it.copy(quickConnect = null, loggedIn = true, errorMessage = null) }
                    openLibraries()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(quickConnect = null, errorMessage = "Quick Connect sign-in failed. Please try again.")
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
            runCatching { container.playbackEngine.stop() }
            container.authRepository.logout()
            _state.value = AppUiState()
        }
    }

    // ----- gallery -----

    fun openLibraries() = viewModelScope.launch {
        _state.update {
            it.copy(
                route = Route.GALLERY, galleryLoading = true, folderStack = emptyList(), items = emptyList(),
                errorMessage = null, searchQuery = "", searchResults = emptyList(), searching = false,
            )
        }
        loadRoots()
    }

    /** Active browsable source (Jellyfin / on-device), per [AppUiState.activeSourceId]. */
    private fun activeSource(): MediaSource =
        container.mediaSources.firstOrNull { it.id == _state.value.activeSourceId } ?: container.jellyfinMediaSource

    /** Sources for the gallery switcher, as (id, displayName) in display order. */
    val sources: List<Pair<String, String>> get() = container.mediaSources.map { it.id to it.displayName }

    /** True when the on-device source has any granted access (a folder, files, or the media permission). */
    fun localHasAccess(): Boolean = container.localMediaSource.hasAnyAccess()

    /** Whether the elective media-library permission is currently granted. */
    fun mediaPermissionGranted(): Boolean = container.permissions.hasReadMediaVideo()

    /** Runtime permission to request for the optional "all device videos" local gallery. */
    val readMediaVideoPermission: String = AndroidNetworkPermissionManager.PERMISSION_READ_MEDIA_VIDEO

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
        viewModelScope.launch { loadRoots() }
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
        if (granted) reloadIfLocalActive()
    }

    private fun reloadIfLocalActive() {
        if (_state.value.activeSourceId != MediaSourceIds.LOCAL) return
        _state.update { it.copy(galleryLoading = true, folderStack = emptyList(), items = emptyList()) }
        viewModelScope.launch { loadRoots() }
    }

    private suspend fun loadRoots() {
        // The Jellyfin source needs a logged-in server; if there's none, show the connect prompt
        // (the gallery) rather than a failed load — local videos still work without Jellyfin.
        if (_state.value.activeSourceId == MediaSourceIds.JELLYFIN && !_state.value.loggedIn) {
            _state.update { it.copy(libraries = emptyList(), continueWatching = emptyList(), galleryLoading = false) }
            return
        }
        activeSource().roots().fold(
            onSuccess = { libs -> _state.update { it.copy(libraries = libs, galleryLoading = false) } },
            onFailure = { e ->
                if (isSessionExpired(e)) handleSessionExpired()
                else _state.update { it.copy(galleryLoading = false, libraries = emptyList(), errorMessage = libraryErrorMessage(e)) }
            },
        )
        loadContinueWatching()
    }

    /**
     * Load the "Continue Watching" row (Jellyfin resume list) alongside the library roots. Best-effort:
     * a failure just leaves the row empty (the libraries below still load). Sources without a server-side
     * watch state (on-device local) return empty, which clears any previously-shown row.
     */
    private suspend fun loadContinueWatching() {
        val items = activeSource().continueWatching().getOrDefault(emptyList())
        _state.update { it.copy(continueWatching = items) }
    }

    fun onItemClicked(item: MediaItem) {
        if (_state.value.searchQuery.isNotBlank()) clearSearch()
        if (item.isFolder) openFolder(item) else openDetail(item)
    }

    private fun openFolder(folder: MediaItem) = viewModelScope.launch {
        _state.update { it.copy(folderStack = it.folderStack + folder, galleryLoading = true, items = emptyList(), errorMessage = null) }
        loadChildren(folder.id)
    }

    /** Go up one level in the gallery; returns to the welcome/library root from the top. */
    fun popFolder() = viewModelScope.launch {
        val stack = _state.value.folderStack
        if (stack.isEmpty()) return@launch
        val newStack = stack.dropLast(1)
        _state.update { it.copy(folderStack = newStack, items = emptyList(), galleryLoading = newStack.isNotEmpty()) }
        newStack.lastOrNull()?.let { loadChildren(it.id) }
    }

    private suspend fun loadChildren(parentId: String) {
        activeSource().children(parentId).fold(
            onSuccess = { kids -> _state.update { it.copy(items = kids, galleryLoading = false) } },
            onFailure = { e ->
                if (isSessionExpired(e)) handleSessionExpired()
                else _state.update { it.copy(galleryLoading = false, errorMessage = libraryErrorMessage(e)) }
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
        viewModelScope.launch { runCatching { container.authRepository.logout() } }
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
        _state.update { it.copy(selectedItem = withLocalResume(item), route = Route.MEDIA_DETAIL, errorMessage = null) }
        // Enrich with full details (chapters power the seek-preview scrubber) in the background — the
        // gallery item carries only summary fields. Best-effort: if it fails (e.g. offline), or the user
        // has since selected a different item, the summary item is kept.
        viewModelScope.launch {
            activeSource().item(item.id).onSuccess { full ->
                _state.update { st -> if (st.selectedItem?.id == item.id) st.copy(selectedItem = withLocalResume(full)) else st }
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
        val folder = _state.value.currentFolder
        if (folder == null) {
            openLibraries()
        } else {
            viewModelScope.launch {
                _state.update { it.copy(galleryLoading = true, errorMessage = null) }
                loadChildren(folder.id)
            }
        }
    }

    // ----- library search -----

    private var searchJob: Job? = null

    /** Debounced free-text search across the whole library; a blank query restores the normal view. */
    fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query, searching = query.isNotBlank()) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            activeSource().search(query.trim()).fold(
                onSuccess = { results -> _state.update { it.copy(searchResults = results, searching = false) } },
                onFailure = { e ->
                    if (isSessionExpired(e)) handleSessionExpired()
                    else _state.update { it.copy(searching = false, errorMessage = libraryErrorMessage(e)) }
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

    fun play() = viewModelScope.launch {
        if (!container.permissions.hasLocalNetworkAccess()) {
            onLocalNetworkPermissionDenied()
            _state.update { it.copy(playbackStartingTargetId = null) }
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
        currentResumeKey = null
        currentDurationSeconds = null
        val requestedSmartResumePosition = smartResumeOverrideSeconds
        val s = _state.value
        val physicalTv = s.selectedPhysicalTv ?: run {
            _state.update { it.copy(playbackStartingTargetId = null) }
            return@launch
        }
        val target = physicalTv.selectEndpoint() ?: run {
            _state.update { it.copy(playbackStartingTargetId = null) }
            return@launch
        }
        val resumeDeviceContext = smartResumeDeviceContext(physicalTv, target)
        _state.update { it.copy(selectedTarget = target) }
        val controller = if (target.protocol == Protocol.CAST) container.castController else container.dlnaController
        val downloadId = s.selectedDownloadId
        // Navigate to the playback screen only AFTER the proxy service enters the foreground (via the
        // onForegrounded callback below), so the screen's recomposition can't queue ahead of onStartCommand
        // on the main thread and blow the startForegroundService() deadline
        // (ForegroundServiceDidNotStartInTimeException on a Galaxy S24 / Android 16). See PlaybackEngine.
        val pendingPlayback = PlaybackUiState(
            targetName = physicalTv.displayName,
            protocol = target.protocol.name,
            mediaTitle = s.selectedItem?.title ?: downloadId?.let(downloadTitles::get).orEmpty(),
            durationSeconds = s.selectedItem?.runtimeSeconds,
            phase = PlaybackPhase.CONNECTING,
            adaptiveNote = "Connecting to the TV…",
            volumeSupported = target.discoveryMetadata.volumeControlAvailable,
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
        runCatching {
            if (downloadId != null) {
                val entry = container.downloadStore.get(downloadId) ?: error("Download no longer available.")
                val file = container.downloadStore.fileFor(entry)
                val resumeKey = "dl:$downloadId"
                reconnectContext = ReconnectContext.Local(
                    file.absolutePath, entry.mimeType, entry.title, entry.runtimeSeconds,
                    physicalTv, target, downloadId, downloadedSmartResumeSeed(entry),
                )
                container.playbackEngine.playLocal(
                    filePath = file.absolutePath,
                    contentType = entry.mimeType,
                    title = entry.title,
                    runtimeSeconds = entry.runtimeSeconds,
                    allowClientTranscode = container.playbackPreferences.transcodeLocalOnDevice,
                    resumePositionSeconds = requestedSmartResumePosition ?: container.resumeStore.resumePosition(resumeKey) ?: 0L,
                    smartResumeSeed = downloadedSmartResumeSeed(entry),
                    smartResumeDeviceContext = resumeDeviceContext,
                    target = controller,
                    selectedTarget = target,
                    onForegrounded = goToPlayback,
                )
                currentResumeKey = resumeKey
                currentDurationSeconds = entry.runtimeSeconds
            } else {
                val item = s.selectedItem ?: error("Nothing selected to play.")
                if (item.sourceId == MediaSourceIds.LOCAL) {
                    // On-device file: served via the proxy from a content:// fd. Resumes where you left
                    // off and auto-reconnects, the same as an online session.
                    val mime = container.localMediaSource.mimeTypeFor(item.id)
                    reconnectContext = ReconnectContext.Local(
                        item.id, mime, item.title, item.runtimeSeconds,
                        physicalTv, target, null, localSmartResumeSeed(item),
                    )
                    container.playbackEngine.playLocal(
                        filePath = item.id,
                        contentType = mime,
                        title = item.title,
                        runtimeSeconds = item.runtimeSeconds,
                        allowClientTranscode = container.playbackPreferences.transcodeLocalOnDevice,
                        resumePositionSeconds = requestedSmartResumePosition ?: container.resumeStore.resumePosition(item.id) ?: 0L,
                        smartResumeSeed = localSmartResumeSeed(item),
                        smartResumeDeviceContext = resumeDeviceContext,
                        target = controller,
                        selectedTarget = target,
                        onForegrounded = goToPlayback,
                    )
                    currentResumeKey = item.id
                    currentDurationSeconds = item.runtimeSeconds
                } else {
                    // Arm seamless autoplay-next: for an eligible series episode the engine holds the TV
                    // connection at end-of-media so the next episode loads without a reconnect.
                    val autoAdvance = container.playbackPreferences.autoPlayNextEpisode &&
                        item.type.equals("Episode", ignoreCase = true) && item.seriesId != null
                    reconnectContext = ReconnectContext.Online(item, physicalTv, target)
                    container.playbackEngine.play(
                        item, controller, target,
                        streamPreferences(),
                        autoAdvance = autoAdvance,
                        smartResumeSeed = onlineSmartResumeSeed(item),
                        smartResumeDeviceContext = resumeDeviceContext,
                        onForegrounded = goToPlayback,
                    )
                }
            }
        }.onSuccess {
            smartResumeOverrideSeconds = null
        }.onFailure { e ->
            if (e is CancellationException) {
                throw e
            } else if (isSessionExpired(e)) {
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
                if (switched) {
                    smartResumeOverrideSeconds = null
                    container.logger.event("playback", "Initial playback recovered through the TV's alternate protocol")
                } else if (context != null) {
                    // Keep raw/redacted transport detail in diagnostics; the main UI stays concise.
                    container.logger.event("playback", "Initial playback exhausted: $reason")
                    surfaceTerminalPlaybackFailure(context, "Couldn't start playback on this TV.")
                } else {
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
    }

    /**
     * A genuine end-of-media finished. If autoplay-next is enabled and the finished item is a Jellyfin
     * series episode with a following episode, play it on the same TV; otherwise fall through to the
     * gallery (the status->null "ended" handler). [autoAdvancing] suppresses that gallery bounce during
     * the hand-off; play().join() holds it until the next episode is loaded (or the attempt fails).
     */
    private fun onEndOfMedia() {
        val current = _state.value.selectedItem
        val eligible = container.playbackPreferences.autoPlayNextEpisode &&
            current != null &&
            current.sourceId == MediaSourceIds.JELLYFIN &&
            current.type.equals("Episode", ignoreCase = true) &&
            current.seriesId != null &&
            _state.value.selectedTarget != null
        if (!eligible) return
        autoAdvancing = true
        viewModelScope.launch {
            try {
                val next = runCatching { container.jellyfinClient.nextEpisode(current!!.seriesId!!, current.id) }
                    .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
                    .getOrNull()
                if (next != null) {
                    container.logger.event("playback", "Autoplaying next episode")
                    _state.update { it.copy(selectedItem = next) }
                    val target = _state.value.selectedTarget
                    val previousContext = reconnectContext
                    // Prefer a SEAMLESS hand-off that reuses the live TV connection (the engine held it open
                    // at end-of-media). Fall back to a full play (reconnect) only if there's no live session.
                    val seamless = target != null && runCatching {
                        container.playbackEngine.playNext(next, streamPreferences(), onlineSmartResumeSeed(next))
                    }.onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }.getOrDefault(false)
                    if (seamless && target != null && previousContext != null) {
                        reconnectContext = ReconnectContext.Online(next, previousContext.physicalTv, target)
                    } else {
                        play().join() // no live session to reuse — full (re)connect
                    }
                } else {
                    // Series finished (no next episode): stop (releasing the held connection) and go home.
                    runCatching { container.playbackEngine.stop() }
                    _state.update { it.copy(route = Route.GALLERY, playback = null) }
                    loadContinueWatching()
                }
            } finally {
                autoAdvancing = false
            }
        }
    }

    /** Resume the app-wide renderer-confirmed checkpoint through the normal target picker. */
    fun resumeSmartResume() = viewModelScope.launch {
        val record = container.smartResumeStore.current ?: return@launch
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
                    val entry = container.downloadStore.get(record.mediaId) ?: error("The downloaded copy was deleted.")
                    val file = container.downloadStore.fileFor(entry)
                    check(file.isFile && file.canRead() && file.length() > 0L) { "The downloaded copy is unavailable." }
                    val position = SmartResumePositionReconciler.reconcile(
                        record,
                        rendererConfirmedSeconds = container.resumeStore.resumePosition("dl:${entry.itemId}"),
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
        val current = container.authRepository.currentUser.value
        check(current?.serverId == record.serverId && current?.userId == record.userId) {
            "Sign in to the Jellyfin account that played this video to resume it."
        }
    }

    private fun onlineSmartResumeSeed(item: MediaItem): SmartResumeSeed? =
        container.authRepository.currentUser.value?.let { user ->
            SmartResumeSeed(SmartResumeSourceType.JELLYFIN, item.id, item.title, item.subtitle, item.runtimeSeconds, user.serverId, user.userId)
        }

    private fun downloadedSmartResumeSeed(entry: DownloadEntry): SmartResumeSeed? =
        container.authRepository.currentUser.value?.let { user ->
            SmartResumeSeed(SmartResumeSourceType.DOWNLOADED, entry.itemId, entry.title, durationSeconds = entry.runtimeSeconds, serverId = user.serverId, userId = user.userId)
        }

    private fun localSmartResumeSeed(item: MediaItem): SmartResumeSeed =
        SmartResumeSeed(SmartResumeSourceType.LOCAL, item.id, item.title, item.subtitle, item.runtimeSeconds, localContentUri = item.id)

    // ----- offline downloads -----

    fun downloadItem(item: MediaItem, format: DownloadFormat = DownloadFormat.Original) {
        downloadTitles[item.id] = item.title
        // Enqueue (this persists the request and sets the Queued state) BEFORE starting the service, so
        // the service's idle check always observes the new download and can't stop before it begins.
        container.downloader.download(item, format)
        container.startDownloadService()
    }

    fun downloadSelected(format: DownloadFormat = DownloadFormat.Original) {
        _state.value.selectedItem?.let { downloadItem(it, format) }
    }

    fun cancelDownload(itemId: String) = container.downloader.cancel(itemId)

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        container.downloader.delete(itemId)
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
        runCatching { container.playbackEngine.selectAudioTrack(index) }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
        // Remember the chosen audio LANGUAGE for this show, so the next episode auto-selects it. Only when
        // the track carries a language (an untagged track can't be re-matched by language next time).
        rememberShowLanguage { key ->
            _state.value.playback?.audioTracks?.firstOrNull { it.index == index }?.language
                ?.let { container.showLanguageStore.rememberAudio(key, it) }
        }
    }

    /** Enable subtitles at [index] (burned in) or disable them (null). Re-resolves the stream. */
    fun selectSubtitleTrack(index: Int?) = viewModelScope.launch {
        runCatching { container.playbackEngine.selectSubtitleTrack(index) }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
        // Remember the subtitle choice for this show: OFF when disabled, else the chosen language (an
        // untagged subtitle track is left as-is, since it can't be re-matched by language next time).
        rememberShowLanguage { key ->
            if (index == null) {
                container.showLanguageStore.rememberSubtitle(key, null) // remembered OFF
            } else {
                _state.value.playback?.subtitleTracks?.firstOrNull { it.index == index }?.language
                    ?.let { container.showLanguageStore.rememberSubtitle(key, it) }
            }
        }
    }

    /** Run [remember] with the current show's memory key (series id for episodes, else item id). No-op if none. */
    private inline fun rememberShowLanguage(remember: (showKey: String) -> Unit) {
        showLanguageKey(_state.value.selectedItem)?.let(remember)
    }

    /** Pin a specific streaming quality (bitrate), or return to Auto (null). Re-resolves the stream. */
    fun selectQuality(bitrateBps: Long?) = viewModelScope.launch {
        runCatching { container.playbackEngine.selectQuality(bitrateBps) }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
    }

    /** Pin the transcode video codec (e.g. "hevc"), or return to automatic (null). Re-resolves the stream. */
    /** Override the stream resolution for this playback only; null returns to the saved automatic cap. */
    fun selectMaxVideoHeight(height: Int?) = viewModelScope.launch {
        runCatching { container.playbackEngine.selectMaxVideoHeight(height) }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
    }

    fun selectPreferredCodec(codec: String?) = viewModelScope.launch {
        runCatching { container.playbackEngine.selectPreferredCodec(codec) }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
    }

    /** Skip the intro/outro/recap segment covering the current position (the "Skip" button). */
    fun skipSegment() = viewModelScope.launch {
        runCatching { container.playbackEngine.skipActiveSegment() }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
    }

    /**
     * Mark a Jellyfin item watched/unwatched using its native watch state. For a series/season this
     * cascades to the child episodes server-side. Updates the UI optimistically, then reconciles the
     * current folder + Continue Watching from the server (so folder/series rollups are accurate).
     */
    fun markWatched(item: MediaItem, played: Boolean) = viewModelScope.launch {
        if (item.sourceId != MediaSourceIds.JELLYFIN) return@launch
        runCatching { container.jellyfinClient.markPlayed(item.id, played) }
            .onSuccess {
                applyWatchedOptimistically(item.id, played)
                refreshWatchStateFromServer()
            }
            .onFailure { e -> if (isSessionExpired(e)) handleSessionExpired() }
    }

    /** Immediately reflect a watched/unwatched change across every list + the open detail item. */
    private fun applyWatchedOptimistically(itemId: String, played: Boolean) = _state.update { st ->
        fun update(list: List<MediaItem>) = list.map { if (it.id == itemId) it.withWatched(played) else it }
        st.copy(
            selectedItem = st.selectedItem?.let { if (it.id == itemId) it.withWatched(played) else it },
            items = update(st.items),
            searchResults = update(st.searchResults),
            libraries = update(st.libraries),
            // A watched item leaves Continue Watching; an unwatched one is reconciled by the refresh below.
            continueWatching = if (played) st.continueWatching.filterNot { it.id == itemId } else st.continueWatching,
        )
    }

    /** Reconcile watch state from the server after a change (folder/series rollups, resume row). */
    private fun refreshWatchStateFromServer() = viewModelScope.launch {
        _state.value.currentFolder?.let { loadChildren(it.id) } // silent (no loading flash)
        loadContinueWatching()
    }

    private fun MediaItem.withWatched(played: Boolean): MediaItem = copy(
        played = played,
        playedPercentage = if (played) 100.0 else 0.0,
        resumePositionSeconds = null,
        unplayedItemCount = if (played) 0 else unplayedItemCount,
    )

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
        cancelReconnect()
        smartResumeOverrideSeconds = lastPlaybackPositionSeconds
        if (ctx is ReconnectContext.Local && ctx.downloadId != null) {
            _state.update { it.copy(selectedDownloadId = ctx.downloadId) }
        }
        _state.update {
            it.copy(
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

    private suspend fun playContext(
        context: ReconnectContext,
        endpoint: DiscoveredTarget,
        resumeAtSeconds: Long,
        recoveryContinuation: PlaybackRecoveryContinuation? = null,
    ) {
        val deviceContext = smartResumeDeviceContext(context.physicalTv, endpoint)
        when (context) {
            is ReconnectContext.Online -> container.playbackEngine.play(
                context.item,
                controllerFor(endpoint),
                endpoint,
                streamPreferences(),
                resumePositionOverrideSeconds = resumeAtSeconds,
                autoAdvance = container.playbackPreferences.autoPlayNextEpisode &&
                    context.item.type.equals("Episode", ignoreCase = true) && context.item.seriesId != null,
                smartResumeSeed = onlineSmartResumeSeed(context.item),
                smartResumeDeviceContext = deviceContext,
                recoveryContinuation = recoveryContinuation,
            )
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
        reconnectContext = null
        pendingResumeDeviceContext = null
        saveResumeNow()
        runCatching { container.playbackEngine.stop() }
        _state.update { it.copy(route = Route.GALLERY) }
        // Persist the playback session's events now so they're in a report even if the app is later
        // killed, and refresh the resume row from the server's updated position.
        container.flushDiagnostics()
        // The server now has an updated resume point for what we just watched — refresh the row.
        loadContinueWatching()
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
                    container.logger.event("playback", "Connection lost; trying the same TV endpoint once")
                    delay(RECONNECT_DELAY_MS)
                    if (generation != reconnectGeneration) return@launch
                    if (container.playbackEngine.retrySameEndpointAfterDisconnect()) {
                        container.logger.event("playback", "Reconnected and resumed on the same endpoint")
                        return@launch
                    }
                }
                if (generation != reconnectGeneration) return@launch
                val latest = container.playbackEngine.recoverySnapshot().attempts.lastOrNull()
                val switched = tryAlternateProtocol(
                    originalContext,
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
                    originalContext,
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
                surfaceTerminalPlaybackFailure(originalContext, "Playback couldn't be restored automatically.")
            } finally {
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
            playContext(context, alternate, lastPlaybackPositionSeconds, continuation)
            if (generation != reconnectGeneration) return false
            reconnectContext = context.withEndpoint(alternate)
            _state.update {
                it.copy(
                    selectedPhysicalTv = context.physicalTv,
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
                volumeSupported = context.endpoint.discoveryMetadata.volumeControlAvailable,
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
    private fun streamPreferences(): StreamPreferences {
        // Resolve the desired languages for THIS item: a per-show memory (last used for the series/movie)
        // wins over the global preferred language; with neither, the server default / no-subtitles apply.
        val showKey = showLanguageKey(_state.value.selectedItem)
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
            container.deleteAllData()
            _state.value = AppUiState() // reset to a clean welcome state
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
        const val SEARCH_DEBOUNCE_MS = 350L
        const val RECONNECT_DELAY_MS = 2_000L
        const val RESUME_SAVE_INTERVAL_MS = 10_000L
    }
}
