package com.adsamcik.streamferry.app

import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.playback.SavedQueueLibrary
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.state.SourceRetryStatus
import com.adsamcik.streamferry.ui.theme.ThemeMode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/** Controller tests run on the JVM; no simulated or physical Android API is needed. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelSourceRetryTest {
    private val container = mockk<AppContainer>(relaxed = true)
    private val owner = UserSession("user", "server")
    private val live = MutableStateFlow<UserSession?>(null)
    private val monitor = JellyfinConnectionMonitor()
    private val saved = MediaItem("library", "Saved library", null, null, null, null, true)
    private lateinit var vm: MainViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { container.savedQueueStore.read() } returns SavedQueueLibrary()
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(false)
        every { container.playbackEngine.status } returns MutableStateFlow(null)
        every { container.playbackEngine.endOfMedia } returns MutableSharedFlow()
        every { container.authRepository.currentUser } returns live
        every { container.authRepository.cachedSession } returns MutableStateFlow(owner)
        every { container.jellyfinConnectionMonitor } returns monitor
        every { container.smartResumeStore.history } returns MutableStateFlow(emptyList())
        every { container.downloader.states } returns MutableStateFlow(emptyMap())
        every { container.downloadStore.metadataRevision } returns MutableStateFlow(0L)
        every { container.mediaSources } returns listOf(container.jellyfinMediaSource, container.localMediaSource)
        every { container.jellyfinMediaSource.id } returns MediaSourceIds.REMOTE
        every { container.localMediaSource.id } returns MediaSourceIds.LOCAL
        coEvery { container.authRepository.restoreCachedSession() } returns owner
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.failure(IllegalStateException("Offline"))
        coEvery { container.jellyfinMediaSource.roots() } returns Result.success(listOf(saved))
        coEvery { container.jellyfinMediaSource.continueWatching() } returns Result.success(emptyList())
        coEvery { container.localMediaSource.roots() } returns Result.success(emptyList())
        coEvery { container.localMediaSource.continueWatching() } returns Result.success(emptyList())
    }

    @After fun cleanup() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.openGallery() {
        vm = MainViewModel(container)
        runCurrent()
        vm.navigate(Route.GALLERY)
        runCurrent()
        assertEquals(listOf(saved), vm.state.value.libraries)
    }

    @Test fun `retry reports progress immediately and cached fallback reports failure without clearing content`() = runTest {
        openGallery()
        val auth = container.authRepository
        val response = CompletableDeferred<Result<UserSession>>()
        coEvery { auth.ensureOnlineSession() } coAnswers { response.await() }
        clearMocks(auth, answers = false)

        vm.refreshGallery()
        vm.refreshGallery()
        assertEquals(SourceRetryStatus.RETRYING, vm.state.value.sourceRetryStatus)
        assertEquals(listOf(saved), vm.state.value.libraries)
        runCurrent()
        coVerify(exactly = 1) { auth.ensureOnlineSession() }

        response.complete(Result.failure(IllegalStateException("Offline")))
        advanceUntilIdle()
        assertEquals(SourceRetryStatus.FAILED, vm.state.value.sourceRetryStatus)
        assertEquals(listOf(saved), vm.state.value.libraries)
        assertFalse(vm.state.value.galleryLoading)

        vm.refreshGallery()
        advanceUntilIdle()
        coVerify(exactly = 2) { auth.ensureOnlineSession() }
        assertEquals(SourceRetryStatus.FAILED, vm.state.value.sourceRetryStatus)
    }

    @Test fun `retry stays busy through library loading and verified recovery clears failure`() = runTest {
        openGallery()
        live.value = owner
        runCurrent()
        val response = CompletableDeferred<Result<List<MediaItem>>>()
        coEvery { container.jellyfinMediaSource.roots() } coAnswers { response.await() }
        vm.refreshGallery()
        runCurrent()
        assertEquals(SourceRetryStatus.RETRYING, vm.state.value.sourceRetryStatus)

        val fresh = saved.copy(title = "Updated library")
        monitor.markOnline()
        response.complete(Result.success(listOf(fresh)))
        advanceUntilIdle()
        assertEquals(SourceRetryStatus.IDLE, vm.state.value.sourceRetryStatus)
        assertEquals(listOf(fresh), vm.state.value.libraries)
    }

    @Test fun `retry refreshes the visible search instead of an unseen root list`() = runTest {
        openGallery()
        live.value = owner
        runCurrent()
        coEvery { container.jellyfinMediaSource.search("film") } returns Result.success(listOf(saved))
        vm.onSearchQueryChanged("film")
        advanceUntilIdle()
        val fresh = saved.copy(id = "found", title = "Recovered result")
        coEvery { container.jellyfinMediaSource.search("film") } coAnswers {
            monitor.markOnline()
            Result.success(listOf(fresh))
        }
        vm.refreshGallery()
        runCurrent()
        assertEquals(SourceRetryStatus.RETRYING, vm.state.value.sourceRetryStatus)
        assertEquals(listOf(saved), vm.state.value.searchResults)
        advanceUntilIdle()
        assertEquals("film", vm.state.value.searchQuery)
        assertEquals(listOf(fresh), vm.state.value.searchResults)
        assertEquals(SourceRetryStatus.IDLE, vm.state.value.sourceRetryStatus)
    }

    @Test fun `switching source cancels pending retry and leaves the new source idle`() = runTest {
        openGallery()
        val response = CompletableDeferred<Result<UserSession>>()
        var cancelled = false
        coEvery { container.authRepository.ensureOnlineSession() } coAnswers {
            try { response.await() } finally { cancelled = true }
        }
        vm.refreshGallery()
        runCurrent()
        vm.selectSource(MediaSourceIds.LOCAL)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(MediaSourceIds.LOCAL, vm.state.value.activeSourceId)
        assertEquals(SourceRetryStatus.IDLE, vm.state.value.sourceRetryStatus)
        assertTrue(vm.state.value.libraries.isEmpty())
    }

    @Test fun `cancelled retry cannot clear progress belonging to a replacement retry`() = runTest {
        openGallery()
        coEvery { container.authRepository.ensureOnlineSession() } coAnswers { CompletableDeferred<Result<UserSession>>().await() }
        vm.refreshGallery()
        runCurrent()
        vm.selectSource(MediaSourceIds.LOCAL)
        vm.selectSource(MediaSourceIds.REMOTE)
        vm.refreshGallery()
        runCurrent()
        assertEquals(SourceRetryStatus.RETRYING, vm.state.value.sourceRetryStatus)
    }

    @Test fun `editing the query cancels retry without overwriting the new search`() = runTest {
        openGallery()
        live.value = owner
        runCurrent()
        coEvery { container.jellyfinMediaSource.search("old") } returns Result.success(listOf(saved))
        vm.onSearchQueryChanged("old")
        advanceUntilIdle()
        vm.refreshGallery()
        runCurrent()
        val fresh = saved.copy(title = "New query result")
        coEvery { container.jellyfinMediaSource.search("new") } returns Result.success(listOf(fresh))
        vm.onSearchQueryChanged("new")
        advanceUntilIdle()
        assertEquals(SourceRetryStatus.IDLE, vm.state.value.sourceRetryStatus)
        assertEquals("new", vm.state.value.searchQuery)
        assertEquals(listOf(fresh), vm.state.value.searchResults)
    }
}
