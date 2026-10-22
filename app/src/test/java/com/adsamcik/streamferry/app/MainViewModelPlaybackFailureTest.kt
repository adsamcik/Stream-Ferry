package com.adsamcik.streamferry.app

import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.*
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.*
import com.adsamcik.streamferry.source.api.SourceHttpException
import com.adsamcik.streamferry.ui.theme.ThemeMode
import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.data.download.DownloadOwner
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelPlaybackFailureTest {
    private val container = mockk<AppContainer>(relaxed = true)
    private val owner = UserSession("user", "server")
    private lateinit var vm: MainViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { container.savedQueueStore.read() } returns SavedQueueLibrary()
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(true)
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.playbackEngine.status } returns MutableStateFlow(null)
        every { container.playbackEngine.endOfMedia } returns MutableSharedFlow()
        every { container.playbackEngine.recoverySnapshot() } returns PlaybackRecoverySession()
        every { container.playbackEngine.continuationForExplicitRetry() } returns null
        every { container.authRepository.currentUser } returns MutableStateFlow(owner)
        every { container.authRepository.cachedSession } returns MutableStateFlow(owner)
        every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.ONLINE)
        every { container.smartResumeStore.history } returns MutableStateFlow(emptyList())
        every { container.smartResumeStore.current } returns null
        every { container.jellyfinWatchMutationStore.pendingFor(any(), any(), any()) } returns null
        every { container.downloader.states } returns MutableStateFlow(emptyMap())
        every { container.downloadStore.metadataRevision } returns MutableStateFlow(0L)
        coEvery { container.authRepository.restoreCachedSession() } returns owner
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.success(owner)
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        coEvery { container.localMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        coEvery { container.jellyfinMediaSource.roots() } returns Result.success(emptyList())
        coEvery { container.jellyfinMediaSource.continueWatching() } returns Result.success(emptyList())
    }

    @After fun cleanup() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `startup and explicit retry preserve useful source failure guidance`() = runTest {
        val engine = container.playbackEngine
        every { engine.recoverySnapshot() } returns PlaybackRecoverySession(attempts = listOf(
            PlaybackAttemptDescriptor(1, failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
                failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE),
        ))
        coEvery { engine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(8).invoke()
            throw PlaybackPreparationException("Source failed", SourceHttpException(503))
        }
        vm = MainViewModel(container)
        runCurrent()
        vm.onItemClicked(MediaItem("video", "Video", null, 600, null, null, false))
        vm.selectPhysicalTv(tv())
        advanceUntilIdle()
        val failed = assertNotNull(vm.state.value.playback)
        assertTrue(failed.isTerminal)
        assertTrue(failed.retryAvailable)
        assertContains(assertNotNull(failed.errorMessage), "HTTP 503")
        vm.retryPlayback()
        advanceUntilIdle()
        assertContains(assertNotNull(vm.state.value.playback?.errorMessage), "HTTP 503")
        coVerify(exactly = 2) { engine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `preflight file access failure explains the problem without offering a dead retry`() = runTest {
        every { container.localMediaSource.mimeTypeFor(any()) } throws SecurityException("content://private/file")
        vm = MainViewModel(container)
        runCurrent()
        vm.onItemClicked(MediaItem("content://file", "Local video", null, 600, null, null, false, sourceId = MediaSourceIds.LOCAL))
        vm.selectPhysicalTv(tv())
        advanceUntilIdle()
        val failed = assertNotNull(vm.state.value.playback)
        assertTrue(failed.isTerminal)
        assertFalse(failed.retryAvailable)
        assertContains(assertNotNull(failed.errorMessage), "Allow access")
        assertFalse(failed.errorMessage!!.contains("private"))
    }

    @Test fun `closing playback ignores a noncooperative late retry failure`() = lateRetryCannotRevivePlayback(fail = true)

    @Test fun `closing playback ignores a noncooperative late retry success`() = lateRetryCannotRevivePlayback(fail = false)

    @Test fun `a late retry failure cannot replace a newer playback session`() = lateRetryCannotRevivePlayback(fail = true, startNew = true)

    @Test fun `a late retry success cannot replace a newer playback session`() = lateRetryCannotRevivePlayback(fail = false, startNew = true)

    @Test fun `casting a download captures its identity instead of the prior gallery item`() = downloadedSelectionOwnsQueue(priorGallery = true)

    @Test fun `casting a legacy download without a gallery selection retains its original account owner`() = downloadedSelectionOwnsQueue(priorGallery = false)

    private fun downloadedSelectionOwnsQueue(priorGallery: Boolean) = runTest {
        val file = kotlin.io.path.createTempFile("download-Y", ".mp4").toFile().apply { writeText("video") }
        try {
            val entry = DownloadEntry("Y", "Video Y", file.name, "video/mp4", runtimeSeconds = 600,
                owner = DownloadOwner(owner.serverId, owner.userId))
            coEvery { container.downloadStore.get(entry.owner!!, "Y") } returns entry
            every { container.downloadStore.fileFor(entry) } returns file
            val engine = container.playbackEngine
            coEvery { engine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<() -> Unit>(10).invoke()
            }
            vm = MainViewModel(container)
            runCurrent()
            if (priorGallery) vm.onItemClicked(MediaItem("X", "Video X", null, 600, null, null, false))
            vm.prepareCastDownload("Y")
            vm.selectPhysicalTv(tv())
            advanceUntilIdle()
            assertEquals("Y", vm.state.value.nowPlayingItem?.id)
            assertEquals("Video Y", vm.state.value.playback?.mediaTitle)
            if (!priorGallery) {
                (container.authRepository.currentUser as MutableStateFlow<UserSession?>).value = UserSession("another-user", "another-server")
                runCurrent()
            }
            vm.saveQueue("Downloaded playback")
            assertEquals("Y", vm.state.value.queues.working.current?.item?.id)
            assertEquals(owner, vm.state.value.queues.working.current?.owner)
        } finally { file.delete() }
    }

    @Test fun `back from search folder restores the parent query and folders`() = runTest {
        val source = container.jellyfinMediaSource
        val series = MediaItem("series", "Series", null, null, null, null, true, type = "Series")
        val season = MediaItem("season", "Season", null, null, null, null, true, type = "Season")
        coEvery { source.children(any()) } returns Result.success(listOf(season))
        coEvery { source.search("Season") } returns Result.success(listOf(season))
        vm = MainViewModel(container)
        runCurrent()
        vm.onItemClicked(series)
        advanceUntilIdle()
        vm.onSearchQueryChanged("Season")
        advanceUntilIdle()
        vm.onItemClicked(season)
        advanceUntilIdle()
        assertEquals("", vm.state.value.searchQuery)
        vm.popFolder()
        advanceUntilIdle()
        assertEquals("Season", vm.state.value.searchQuery)
        assertEquals(listOf(series), vm.state.value.folderStack)
        assertEquals(listOf(season), vm.state.value.searchResults)
        vm.clearSearch()
        vm.popFolder()
        advanceUntilIdle()
        assertTrue(vm.state.value.folderStack.isEmpty())
    }

    private fun lateRetryCannotRevivePlayback(fail: Boolean, startNew: Boolean = false) = runTest {
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val engine = container.playbackEngine
        every { engine.recoverySnapshot() } returns PlaybackRecoverySession(attempts = listOf(
            PlaybackAttemptDescriptor(1, failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
                failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE),
        ))
        coEvery { engine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(8).invoke()
            when (++starts) {
                1 -> throw PlaybackPreparationException("Source failed", SourceHttpException(503))
                2 -> {
                    withContext(NonCancellable) { release.await() }
                    if (fail) throw IllegalStateException("Late old retry error")
                }
            }
        }
        vm = MainViewModel(container)
        runCurrent()
        vm.onItemClicked(MediaItem("old", "Old video", null, 600, null, null, false))
        vm.selectPhysicalTv(tv())
        advanceUntilIdle()
        vm.retryPlayback()
        runCurrent()
        vm.stopPlayback()
        runCurrent()
        assertEquals(com.adsamcik.streamferry.ui.state.Route.GALLERY, vm.state.value.route)
        assertNull(vm.state.value.playback)
        if (startNew) {
            vm.onItemClicked(MediaItem("new", "New video", null, 600, null, null, false))
            vm.selectPhysicalTv(tv())
            runCurrent()
        }
        release.complete(Unit)
        advanceUntilIdle()
        if (startNew) {
            assertEquals(com.adsamcik.streamferry.ui.state.Route.PLAYBACK, vm.state.value.route)
            assertEquals("new", vm.state.value.nowPlayingItem?.id)
            assertFalse(vm.state.value.playback!!.isTerminal)
        } else {
            assertEquals(com.adsamcik.streamferry.ui.state.Route.GALLERY, vm.state.value.route)
            assertNull(vm.state.value.playback)
            assertNull(vm.state.value.nowPlayingItem)
        }
    }

    @Test fun `failed alternate protocol explains its final phone service error instead of the original TV error`() = runTest {
        val engine = container.playbackEngine
        var attempt = PlaybackAttemptDescriptor(1, protocol = Protocol.CAST.name,
            failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION,
            failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE)
        every { engine.recoverySnapshot() } answers { PlaybackRecoverySession(
            attempts = listOf(attempt), usage = RecoveryBudgetUsage(
                automaticAttempts = 2, endpointConnectionRetries = mapOf(Protocol.CAST to 2))) }
        every { engine.reserveAlternateProtocolContinuation(any(), any(), any(), any(), any(), any()) } returns
            mockk<PlaybackRecoveryContinuation>(relaxed = true)
        val protocols = mutableListOf<Protocol>()
        coEvery { engine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(8).invoke()
            val endpoint = arg<DiscoveredTarget>(2)
            protocols += endpoint.protocol
            if (endpoint.protocol == Protocol.CAST) throw IllegalStateException("Cast connection failed")
            attempt = PlaybackAttemptDescriptor(2, protocol = Protocol.DLNA.name,
                failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
                failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE)
            throw PlaybackPreparationException("The phone playback service was rejected.", SecurityException())
        }
        vm = MainViewModel(container)
        runCurrent()
        vm.onItemClicked(MediaItem("video", "Video", null, 600, null, null, false))
        vm.selectPhysicalTv(tv().copy(castEndpoint = DiscoveredTarget(
            "cast", "TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null)))
        advanceUntilIdle()
        assertEquals(listOf(Protocol.CAST, Protocol.DLNA), protocols)
        val message = assertNotNull(vm.state.value.playback?.errorMessage)
        assertContains(message, "phone couldn't start its playback service")
        assertFalse(message.contains("media source"))
    }

    private fun tv() = PhysicalTv("tv", "TV", dlnaEndpoint = DiscoveredTarget(
        "tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
    ))
}
