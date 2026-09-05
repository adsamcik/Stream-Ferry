package com.adsamcik.streamferry.integration

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.app.AppContainer
import com.adsamcik.streamferry.app.MainViewModel
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.data.cache.CachingMediaLibraryRepository
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.data.cache.LibraryCache
import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.data.download.DownloadIdentity
import com.adsamcik.streamferry.data.download.DownloadOwner
import com.adsamcik.streamferry.data.download.DownloadStore
import com.adsamcik.streamferry.data.jellyfin.*
import com.adsamcik.streamferry.data.resume.ResumeStore
import com.adsamcik.streamferry.data.resume.SmartResumeStore
import com.adsamcik.streamferry.domain.*
import com.adsamcik.streamferry.logging.DiagnosticsLogger
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
    private val completions = MutableSharedFlow<PlaybackCompletion>()
    private val monitor = JellyfinConnectionMonitor()
    @Volatile private var rejectMutation = false
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
        cache = LibraryCache(context, fileWriter = { file, text -> file.parentFile.mkdirs(); file.writeText(text) })
        cache.clear()
        downloads = DownloadStore(context)
        downloads.clear()
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
        val source = JellyfinMediaSource(CachingMediaLibraryRepository(
            JellyfinMediaLibraryRepository(client, logger), cache, { scopeKey }, { users.value == session }, monitor,
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
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(true)
        every { container.authRepository.currentUser } returns users
        every { container.authRepository.cachedSession } returns MutableStateFlow(null)
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
            completions.emit(PlaybackCompletion(null, false, 7))
        }
        withContext(main) { yield() }
        assertTrue(client.itemDetails(item.id).played || journal.pendingFor(session.serverId, session.userId, item.id) != null,
            "Finishing an account-owned download must sync or queue watched state")
    }

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
