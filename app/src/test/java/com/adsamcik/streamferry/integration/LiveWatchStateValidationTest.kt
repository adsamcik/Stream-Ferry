package com.adsamcik.streamferry.integration

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.app.AppContainer
import com.adsamcik.streamferry.app.MainViewModel
import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.data.cache.CachingMediaLibraryRepository
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.data.cache.LibraryCache
import com.adsamcik.streamferry.data.download.DownloadedJellyfinMediaLibraryRepository
import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.data.download.DownloadIdentity
import com.adsamcik.streamferry.data.download.DownloadOwner
import com.adsamcik.streamferry.data.download.DownloadStore
import com.adsamcik.streamferry.data.jellyfin.*
import com.adsamcik.streamferry.data.resume.ResumeStore
import com.adsamcik.streamferry.data.resume.SmartResumeStore
import com.adsamcik.streamferry.domain.*
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.playback.PlaylistEntry
import com.adsamcik.streamferry.playback.PlaybackQueue
import com.adsamcik.streamferry.playback.QueueContents
import com.adsamcik.streamferry.playback.SavedQueueLibrary
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.playback.PlaybackCompletion
import com.adsamcik.streamferry.playback.PlaybackStatus
import com.adsamcik.streamferry.source.api.*
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.theme.ThemeMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import kotlin.test.*

/**
 * Strict acceptance checks against a DISPOSABLE loopback Jellyfin, not a user's library.
 * Opt in with STREAM_FERRY_WATCH_VALIDATION_URL. The fixture must contain a library named
 * WatchValidation with one movie named Validation and user validation / watch-validation-only.
 * Missing opt-in skips; unavailable/misconfigured fixtures and broken acceptance criteria fail.
 * Renderer events are simulated; auth HTTP, mutations, readback, cache, journal and download
 * metadata are real. Reflection seeds otherwise-private playback identity without contacting a TV.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveWatchStateValidationTest {
    private lateinit var base: String
    private lateinit var client: JellyfinClient
    private lateinit var token: String
    private lateinit var session: UserSession
    private lateinit var item: MediaItem
    private lateinit var owner: DownloadOwner
    private lateinit var cache: LibraryCache
    private lateinit var downloads: DownloadStore
    private lateinit var journal: JellyfinWatchMutationStore
    private lateinit var resumes: ResumeStore
    private lateinit var history: SmartResumeStore
    private lateinit var container: AppContainer
    private lateinit var vm: MainViewModel
    private lateinit var main: ExecutorCoroutineDispatcher
    private val http = OkHttpClient()
    private val logger = mockk<DiagnosticsLogger>(relaxed = true)
    private val users = MutableStateFlow<UserSession?>(null)
    private val cachedUsers = MutableStateFlow<UserSession?>(null)
    private val completions = MutableSharedFlow<PlaybackCompletion>()
    private val monitor = JellyfinConnectionMonitor()
    @Volatile private var rejectMutation = false
    @Volatile private var activePlaybackItemId: String? = null
    @Volatile private var automaticReports = true
    private var reportingCoordinator: DefaultPlaybackSessionCoordinator? = null
    private val scopeKey get() = AccountLibraryScope(session.serverId, session.userId).cacheKey

    @Before
    fun setUp() = runBlocking {
        val configured = System.getenv("STREAM_FERRY_WATCH_VALIDATION_URL")
        assumeTrue("Set STREAM_FERRY_WATCH_VALIDATION_URL to a disposable fixture", !configured.isNullOrBlank())
        base = configured!!.trimEnd('/')
        require(java.net.URI(base).host == "127.0.0.1") { "Validation only permits a disposable loopback server" }
        client = JellyfinClient(http, "watch-validation-${UUID.randomUUID()}", "Validation", "test", logger)
        client.configureServer(base)
        val server = client.publicInfo() ?: error("Validation Jellyfin is unavailable")
        val auth = client.authenticateByName("validation", "watch-validation-only")
        token = auth.accessToken
        client.setAuth(token, auth.userId)
        session = UserSession(auth.userId, assertNotNull(server.serverId))
        owner = DownloadOwner(session.serverId, session.userId)
        val library = client.userViews().single { it.title == "WatchValidation" }
        item = client.itemsPage(library.id, 0, 20).items.single { it.title == "Validation" }
        seedRemote(played = false, seconds = 300)
        item = client.itemDetails(item.id)
        assertEquals(300L, item.resumePositionSeconds, "Fixture needs a 20-minute synthetic video")

        val context = RuntimeEnvironment.getApplication()
        downloads = DownloadStore(context)
        downloads.clear()
        cache = LibraryCache(context,
            fileWriter = { file, text -> file.parentFile?.mkdirs(); file.writeText(text) },
            onItemsChanged = downloads::syncLibraryMetadata,
        )
        cache.clear()
        journal = JellyfinWatchMutationStore(context).also { it.clear() }
        resumes = ResumeStore(context).also { it.clear() }
        history = SmartResumeStore(context).also { it.clear() }
        cache.put(scopeKey, LibraryCache.childrenKey(library.id), listOf(item))
        cache.put(scopeKey, LibraryCache.KEY_RESUME, listOf(item))
        val entry = DownloadEntry(item.id, item.title, "${DownloadIdentity(owner, item.id).fileStem}.mp4",
            "video/mp4", sizeBytes = 1, runtimeSeconds = 1200, owner = owner, mediaItem = item)
        downloads.fileFor(entry).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        downloads.upsert(entry)
        resumes.save(DownloadIdentity(owner, item.id).resumeKey, 300, 1200)

        users.value = session
        val source = JellyfinMediaSource(DownloadedJellyfinMediaLibraryRepository(
            delegate = CachingMediaLibraryRepository(
                JellyfinMediaLibraryRepository(client, logger), cache, { scopeKey }, { users.value == session }, monitor,
            ),
            downloadStore = downloads,
            owner = { users.value?.let { DownloadOwner(it.serverId, it.userId) } },
            canRefreshDownloads = { users.value == session },
        ))
        val identity = JellyfinSourceBackend.identity(session.serverId, session.userId, "Validation")
        val provider = JellyfinUserStateProvider(identity.id, client)
        val backend = mockk<SourceBackend>(relaxed = true)
        every { backend.identity } returns identity
        every { backend.userState } returns object : UserStateProvider {
            override suspend fun update(media: MediaRef, state: MediaUserState): Result<MediaUserState> =
                if (rejectMutation) Result.failure(IOException("Injected unavailable server"))
                else provider.update(media, state)
        }
        container = mockk(relaxed = true)
        every { container.savedQueueStore.read() } returns SavedQueueLibrary()
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(true)
        every { container.authRepository.currentUser } returns users
        every { container.authRepository.cachedSession } returns cachedUsers
        coEvery { container.authRepository.restoreCachedSession() } returns null
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.success(session)
        coEvery { container.authRepository.runWithActiveSession(any(), any()) } coAnswers {
            if (firstArg<UserSession>() != users.value) false else {
                secondArg<suspend () -> Unit>().invoke()
                true
            }
        }
        every { container.jellyfinClient } returns client
        every { container.jellyfinMediaSource } returns source
        every { container.sourceRegistry() } returns SourceRegistry(listOf(backend))
        every { container.libraryCache } returns cache
        every { container.jellyfinWatchMutationStore } returns journal
        every { container.downloadStore } returns downloads
        every { container.resumeStore } returns resumes
        every { container.smartResumeStore } returns history
        every { container.jellyfinConnectionMonitor } returns monitor
        every { container.downloader.states } returns MutableStateFlow(emptyMap())
        coEvery { container.downloader.hasPendingPersisted(any()) } returns false
        every { container.playbackEngine.status } returns MutableStateFlow<PlaybackStatus?>(null)
        every { container.playbackEngine.endOfMedia } returns completions
        every { container.playbackEngine.isCurrentPlaybackGeneration(any()) } answers { firstArg<Long>() == 7L }
        every { container.playbackEngine.suppressAutomaticWatchState(any(), any(), any()) } answers {
            val matches = firstArg<String>() == session.serverId && secondArg<String>() == session.userId &&
                thirdArg<String>() == activePlaybackItemId
            if (matches) automaticReports = false
            matches
        }
        every { container.playbackEngine.hasManualWatchStateOverride(any()) } answers { firstArg<Long>() == 7L && !automaticReports }
        coEvery { container.playbackEngine.awaitPendingAutomaticReports() } coAnswers { reportingCoordinator?.awaitPendingReports() ?: Unit }
        every { container.playbackPreferences.autoPlayNextEpisode } returns false
        main = newSingleThreadContext("watch-validation-main")
        Dispatchers.setMain(main)
        vm = withContext(main) { MainViewModel(container) }
        eventually { vm.state.value.loggedIn && completions.subscriptionCount.value == 1 }
        withContext(main) { uiState().value = vm.state.value.copy(selectedItem = item, items = listOf(item), continueWatching = listOf(item)) }
    }

    @After
    fun tearDown() = runBlocking {
        if (::vm.isInitialized) withContext(main) { vm.viewModelScope.cancel() }
        if (::main.isInitialized) { Dispatchers.resetMain(); main.close() }
        if (::journal.isInitialized) journal.clear()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun currentManualUnwatchedOverridesProgressStopAndCompletion() = runBlocking {
        verifyCurrentManualOverride(played = false)
    }

    @Test
    fun currentManualWatchedOverridesLaterProgress() = runBlocking {
        verifyCurrentManualOverride(played = true)
    }

    private suspend fun verifyCurrentManualOverride(played: Boolean) {
        activatePlayback(item.id)
        val coordinator = startRealReporting()
        withContext(main) { vm.markWatched(item, played) }
        eventually { !automaticReports && journal.pendingFor(session.serverId, session.userId).isEmpty() &&
            vm.state.value.watchStateMutationItemIds.isEmpty() && client.itemDetails(item.id).resumePositionSeconds == null }
        coordinator.notePosition(1150)
        coordinator.reportProgress(false)
        coordinator.stop("completed")
        withContext(main) { completions.emit(PlaybackCompletion(item, true, 7, automaticWatchStateEnabled = { automaticReports })); yield() }
        assertEquals(played, client.itemDetails(item.id).played)
        assertNull(client.itemDetails(item.id).resumePositionSeconds)
        assertTrue(journal.pendingFor(session.serverId, session.userId).isEmpty())
    }

    @Test
    fun editingQueuedCopyDoesNotSuppressCurrentOrItsFuturePlayback() = runBlocking {
        activatePlayback(item.id)
        val current = PlaylistEntry(101, item, session)
        val next = PlaylistEntry(102, item, session)
        withContext(main) {
            setPrivate("playingQueueEntryId", 101L)
            uiState().value = vm.state.value.copy(queues = SavedQueueLibrary(QueueContents(PlaybackQueue(listOf(next)), current)))
            vm.markQueueItemWatched(102, true)
        }
        awaitSynced(true)
        assertTrue(automaticReports, "Editing an upcoming duplicate must not disable the current playback")
        assertTrue(vm.state.value.queues.working.upNext.next!!.item.played)
        withContext(main) { vm.markQueueItemWatched(102, false) }
        eventually { vm.state.value.watchStateMutationItemIds.isEmpty() && client.itemDetails(item.id).resumePositionSeconds == null &&
            !client.itemDetails(item.id).played && journal.pendingFor(session.serverId, session.userId).isEmpty() }
        // When the queued entry starts, it still reports progress and can become watched automatically.
        val coordinator = startRealReporting()
        coordinator.notePosition(1150)
        coordinator.reportProgress(false)
        coordinator.stop("completed next entry")
        assertTrue(client.itemDetails(item.id).played)
    }

    @Test
    fun editingCurrentQueueEntrySuppressesAutomationAndUpdatesItsSnapshot() = runBlocking {
        activatePlayback(item.id)
        withContext(main) {
            setPrivate("playingQueueEntryId", 101L)
            uiState().value = vm.state.value.copy(queues = SavedQueueLibrary(QueueContents(current = PlaylistEntry(101, item, session))))
            vm.markQueueItemWatched(101, true)
        }
        awaitSynced(true)
        assertFalse(automaticReports)
        assertTrue(vm.state.value.queues.working.current!!.item.played)
        assertNull(vm.state.value.queues.working.current!!.item.resumePositionSeconds)
    }

    @Test
    fun offlineManualEditOverridesDownloadedCompletionAndReplaysForCachedOwner() = runBlocking {
        activatePlayback(item.id)
        withContext(main) { cachedUsers.value = session; users.value = null }
        eventually { vm.state.value.hasCachedRemoteSession && !vm.state.value.loggedIn }
        withContext(main) { vm.markWatched(item, false) }
        eventually { journal.pendingFor(session.serverId, session.userId, item.id) != null &&
            vm.state.value.watchStateMutationItemIds.isEmpty() && downloads.get(owner, item.id)?.mediaItem?.resumePositionSeconds == null }
        assertFalse(automaticReports)
        assertEquals(300L, client.itemDetails(item.id).resumePositionSeconds)
        withContext(main) { completions.emit(downloadedCompletion()); yield() }
        assertEquals(JellyfinWatchMutationKind.MARK_UNPLAYED, journal.pendingFor(session.serverId, session.userId, item.id)?.kind)
        withContext(main) { users.value = session }
        eventually { journal.pendingFor(session.serverId, session.userId).isEmpty() && client.itemDetails(item.id).resumePositionSeconds == null }
        assertFalse(client.itemDetails(item.id).played)
    }

    @Test
    fun latestManualEditWinsWhenRequestsOverlap() = runBlocking {
        activatePlayback(item.id)
        withContext(main) { vm.markWatched(item, true); vm.markWatched(item, false) }
        eventually { journal.pendingFor(session.serverId, session.userId).isEmpty() &&
            vm.state.value.watchStateMutationItemIds.isEmpty() &&
            vm.state.value.selectedItem?.played == false && client.itemDetails(item.id).resumePositionSeconds == null }
        assertFalse(client.itemDetails(item.id).played)
        assertFalse(automaticReports)
    }

    private suspend fun activatePlayback(itemId: String) = withContext(main) {
        activePlaybackItemId = itemId
        automaticReports = true
        setPrivate("onlinePlaybackOwner", session)
        uiState().value = vm.state.value.copy(nowPlayingItem = item, playback = PlaybackUiState("Test TV", "CAST", isPlaying = true))
    }

    private suspend fun startRealReporting(): DefaultPlaybackSessionCoordinator {
        val reporter = DefaultJellyfinPlaybackReporter(client, "watch-validation", logger, monitor)
        val info = JellyfinPlaybackInfo(mediaSourceId = item.id, playSessionId = null,
            profile = MediaProfile(container = "mp4", videoCodec = "h264", audioCodec = "aac"), runtimeSeconds = 1200, itemId = item.id)
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 1)
        coEvery { source.report(any()) } coAnswers {
            when (val event = firstArg<PlaybackEvent>()) {
                is PlaybackEvent.Started -> reporter.reportStart(info, event.positionSeconds)
                is PlaybackEvent.Progress -> reporter.reportProgress(info, event.positionSeconds, event.isPaused)
                is PlaybackEvent.Stopped -> reporter.reportStopped(info, event.positionSeconds)
            }
        }
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:19000"
        return DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, logger).also {
            reportingCoordinator = it
            it.startAndBuildUrl(source, "127.0.0.1", 300, shouldReport = { automaticReports })
            it.awaitPendingReports()
        }
    }

    @Test
    fun manualWatchedSynchronizesRemoteCacheDownloadAndResume() = runBlocking {
        withContext(main) { vm.markWatched(item, true) }
        awaitSynced(true)
        assertTrue(client.itemDetails(item.id).played)
        assertTrue(assertNotNull(cache.item(scopeKey, item.id)).played)
        assertTrue(assertNotNull(downloads.get(owner, item.id)?.mediaItem).played)
        assertNull(downloads.get(owner, item.id)?.mediaItem?.resumePositionSeconds)
        assertNull(resumes.get(DownloadIdentity(owner, item.id).resumeKey))
        assertTrue(vm.state.value.continueWatching.none { it.id == item.id })
    }

    @Test
    fun manualUnwatchedKeepsDownloadedProgressConsistentWithRemote() = runBlocking {
        // Rewatching an already watched title can have both Played=true and a resume point.
        seedRemote(played = true, seconds = 300)
        item = client.itemDetails(item.id)
        cache.put(scopeKey, "rewatch", listOf(item))
        downloads.upsert(assertNotNull(downloads.get(owner, item.id)).copy(mediaItem = item))
        withContext(main) { uiState().value = vm.state.value.copy(selectedItem = item) }
        withContext(main) { vm.markWatched(item, false) }
        eventually { journal.pendingFor(session.serverId, session.userId).isEmpty() &&
            vm.state.value.watchStateMutationItemIds.isEmpty() &&
            client.itemDetails(item.id).resumePositionSeconds == null }
        val remote = client.itemDetails(item.id)
        assertEquals(remote.resumePositionSeconds, downloads.get(owner, item.id)?.mediaItem?.resumePositionSeconds,
            "Downloaded metadata must agree with Jellyfin after mark unwatched")
        assertEquals(remote.resumePositionSeconds, resumes.resumePosition(DownloadIdentity(owner, item.id).resumeKey))
    }

    @Test
    fun resetProgressSynchronizesRemoteAndEveryLocalResume() = runBlocking {
        withContext(main) { vm.resetEpisodeProgress(item.copy(type = "Episode")) }
        eventually { client.itemDetails(item.id).resumePositionSeconds == null &&
            journal.pendingFor(session.serverId, session.userId).isEmpty() && vm.state.value.watchStateMutationItemIds.isEmpty() }
        assertFalse(client.itemDetails(item.id).played)
        assertNull(downloads.get(owner, item.id)?.mediaItem?.resumePositionSeconds)
        assertNull(resumes.get(DownloadIdentity(owner, item.id).resumeKey))
    }

    @Test
    fun onlineCompletionAutomaticallyMarksExactItemWatched() = runBlocking {
        withContext(main) { setPrivate("onlinePlaybackOwner", session); completions.emit(PlaybackCompletion(item, true, 7)) }
        awaitSynced(true)
        assertTrue(client.itemDetails(item.id).played)
    }

    @Test
    fun staleOnlineCompletionDoesNotMarkItemWatched() = runBlocking {
        withContext(main) { setPrivate("onlinePlaybackOwner", session); completions.emit(PlaybackCompletion(item, true, 6)) }
        withContext(main) { yield() }
        assertFalse(client.itemDetails(item.id).played)
        assertTrue(journal.pendingFor(session.serverId, session.userId).isEmpty())
    }

    @Test
    fun downloadedCompletionQueuesRemoteWatchedState() = runBlocking {
        withContext(main) {
            uiState().value = vm.state.value.copy(nowPlayingItem = item)
            completions.emit(downloadedCompletion())
        }
        eventually { client.itemDetails(item.id).played || journal.pendingFor(session.serverId, session.userId, item.id) != null }
        awaitSynced(true)
        assertTrue(client.itemDetails(item.id).played)
    }

    @Test
    fun offlineDownloadedCompletionSurvivesRestartAndReplaysForOwner() = runBlocking {
        withContext(main) { users.value = null; yield(); completions.emit(downloadedCompletion()) }
        eventually { journal.pendingFor(session.serverId, session.userId, item.id) != null &&
            downloads.get(owner, item.id)?.mediaItem?.played == true }
        assertFalse(client.itemDetails(item.id).played)
        assertNotNull(JellyfinWatchMutationStore(RuntimeEnvironment.getApplication())
            .pendingFor(session.serverId, session.userId, item.id))
        withContext(main) { users.value = session }
        eventually { client.itemDetails(item.id).played && journal.pendingFor(session.serverId, session.userId).isEmpty() }
    }

    @Test
    fun downloadedCompletionAfterAccountSwitchWaitsForOriginalOwner() = runBlocking {
        withContext(main) { users.value = UserSession("another-account", session.serverId); yield() }
        withContext(main) { completions.emit(downloadedCompletion()) }
        eventually { journal.pendingFor(session.serverId, session.userId, item.id) != null }
        assertFalse(client.itemDetails(item.id).played)
        assertTrue(journal.pendingFor(session.serverId, "another-account").isEmpty())
        withContext(main) { users.value = session }
        eventually { client.itemDetails(item.id).played && journal.pendingFor(session.serverId, session.userId).isEmpty() }
    }

    @Test
    fun staleOrOwnerlessLocalCompletionDoesNotQueueRemoteState() = runBlocking {
        withContext(main) {
            completions.emit(downloadedCompletion().copy(generation = 6))
            completions.emit(PlaybackCompletion(null, false, 7))
            yield()
        }
        assertTrue(journal.pendingFor(session.serverId, session.userId).isEmpty())
        assertFalse(client.itemDetails(item.id).played)
    }

    private fun downloadedCompletion() = PlaybackCompletion(null, false, 7, SmartResumeSeed(
        SmartResumeSourceType.DOWNLOADED, item.id, item.title, durationSeconds = 1200,
        serverId = session.serverId, userId = session.userId,
    ))

    @Test
    fun failedRemoteWritePersistsAndReplaysOnRefresh() = runBlocking {
        rejectMutation = true
        withContext(main) { vm.markWatched(item, true) }
        eventually { journal.pendingFor(session.serverId, session.userId, item.id) != null &&
            vm.state.value.watchStateMutationItemIds.isEmpty() }
        assertFalse(client.itemDetails(item.id).played)
        assertTrue(vm.state.value.selectedItem!!.played)
        val reloadedJournal = JellyfinWatchMutationStore(RuntimeEnvironment.getApplication())
        assertNotNull(reloadedJournal.pendingFor(session.serverId, session.userId, item.id), "Intent must survive store recreation")
        rejectMutation = false
        withContext(main) { vm.refreshGallery() }
        awaitSynced(true)
        assertTrue(client.itemDetails(item.id).played)
    }

    @Test
    fun remoteChangeBecomesVisibleAfterExplicitRefresh() = runBlocking {
        seedRemote(true, 0)
        withContext(main) { vm.onSearchQueryChanged("Validation") }
        eventually { vm.state.value.searchResults.any { it.id == item.id && it.played } }
        assertTrue(assertNotNull(cache.item(scopeKey, item.id)).played,
            "Latest remote watched state must survive a subsequent offline cache lookup")
    }

    @Test
    fun newerCachedWatchStateWinsRegardlessOfSnapshotFilename() = runBlocking {
        cache.clear()
        // SHA-256("a") sorts after SHA-256("b"); chronology must win over that filename order.
        cache.put(scopeKey, "a", listOf(item.copy(played = false)))
        cache.put(scopeKey, "b", listOf(item.copy(played = true)))
        assertTrue(assertNotNull(cache.item(scopeKey, item.id)).played,
            "An older snapshot must not overwrite the most recent item index")
    }

    @Test
    fun remoteChangeAlsoRefreshesTheDownloadedCopy() = runBlocking {
        seedRemote(true, 0)
        withContext(main) { vm.onSearchQueryChanged("Validation") }
        eventually { vm.state.value.searchResults.any { it.id == item.id && it.played } }
        assertTrue(assertNotNull(downloads.get(owner, item.id)?.mediaItem).played,
            "A successful remote refresh must not leave the offline copy with stale watch state")
    }

    @Test
    fun downloadedShelfRefreshFetchesExternalWatchedAndPartialProgressChanges() = runBlocking {
        seedRemote(true, 0)
        val shelf = DownloadedJellyfinMediaLibraryRepository.OFFLINE_DOWNLOADS_ROOT_ID
        assertTrue(container.jellyfinMediaSource.children(shelf).getOrThrow().single().played)
        seedRemote(false, 450)
        val refreshed = container.jellyfinMediaSource.children(shelf).getOrThrow().single()
        assertFalse(refreshed.played)
        assertEquals(450L, refreshed.resumePositionSeconds)
        assertEquals(refreshed, downloads.get(owner, item.id)?.mediaItem)
    }

    @Test
    fun queuedMutationWaitsForItsOwningAccount() = runBlocking {
        rejectMutation = true
        withContext(main) { vm.markWatched(item, true) }
        eventually { journal.pendingFor(session.serverId, session.userId, item.id) != null &&
            vm.state.value.watchStateMutationItemIds.isEmpty() }
        rejectMutation = false
        withContext(main) { users.value = UserSession("another-account", session.serverId); yield() }
        assertFalse(client.itemDetails(item.id).played)
        assertNotNull(journal.pendingFor(session.serverId, session.userId, item.id))
        withContext(main) { users.value = session }
        awaitSynced(true)
        assertTrue(client.itemDetails(item.id).played)
    }

    @Test
    fun serverAutomaticallyMarksNearEndPlaybackButNotPartialPlayback() = runBlocking {
        val reporter = DefaultJellyfinPlaybackReporter(client, "watch-validation", logger, monitor)
        val info = JellyfinPlaybackInfo(mediaSourceId = item.id, playSessionId = null,
            profile = MediaProfile(container = "mp4", videoCodec = "h264", audioCodec = "aac"),
            runtimeSeconds = 1200, itemId = item.id)
        reporter.reportStart(info, 0)
        reporter.reportProgress(info, 300, false)
        reporter.reportStopped(info, 300)
        val partial = client.itemDetails(item.id)
        assertFalse(partial.played)
        assertEquals(300L, partial.resumePositionSeconds)
        reporter.reportStart(info, 300)
        reporter.reportProgress(info, 1150, false)
        reporter.reportStopped(info, 1150)
        val completed = client.itemDetails(item.id)
        assertTrue(completed.played)
        assertNull(completed.resumePositionSeconds)
    }

    private suspend fun awaitSynced(played: Boolean) = eventually {
        vm.state.value.selectedItem?.played == played && vm.state.value.watchStateMutationItemIds.isEmpty() &&
            journal.pendingFor(session.serverId, session.userId).isEmpty()
    }

    private suspend fun eventually(condition: suspend () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(20) }
    }

    private fun seedRemote(played: Boolean, seconds: Long) {
        val body = "{\"Played\":$played,\"PlaybackPositionTicks\":${seconds * 10_000_000L}}"
        val request = Request.Builder().url("$base/UserItems/${item.id}/UserData?userId=${session.userId}")
            .header("X-Emby-Token", token).post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { check(it.isSuccessful) { "Fixture state update failed: ${it.code}" } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun uiState(): MutableStateFlow<AppUiState> = MainViewModel::class.java.getDeclaredField("_state")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppUiState>

    private fun setPrivate(name: String, value: Any) = MainViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)
}
