package com.adsamcik.streamferry.app

import android.app.Application
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.data.download.DownloadOwner
import com.adsamcik.streamferry.data.download.DownloadIdentity
import com.adsamcik.streamferry.data.download.MediaDownloader.DownloadState
import com.adsamcik.streamferry.domain.SourceAvailability
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.TargetDiscoveryMetadata
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.transcode.LocalTranscodeMode
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.physical.EndpointSelectionPreference
import com.adsamcik.streamferry.playback.*
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.ThemeMode
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainViewModelQueueTest {
    private val container = mockk<AppContainer>(relaxed = true)
    private val local = MediaItem("content://media/video/1", "Local video", null, 600, null, null, false, sourceId = "local")
    private var viewModel: MainViewModel? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { container.savedQueueStore.read() } returns SavedQueueLibrary()
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(false)
        every { container.playbackEngine.status } returns MutableStateFlow(null)
        every { container.playbackEngine.endOfMedia } returns MutableSharedFlow()
        every { container.authRepository.currentUser } returns MutableStateFlow(null)
        every { container.authRepository.cachedSession } returns MutableStateFlow(null)
        every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.UNKNOWN)
        every { container.smartResumeStore.history } returns MutableStateFlow(emptyList())
        every { container.downloader.states } returns MutableStateFlow(emptyMap())
        every { container.downloadStore.metadataRevision } returns MutableStateFlow(0L)
        every { container.resumeStore.resumePosition(any()) } returns null
        coEvery { container.authRepository.restoreCachedSession() } returns null
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.failure(IllegalStateException("Offline"))
        coEvery { container.jellyfinMediaSource.roots() } returns Result.success(emptyList())
        coEvery { container.jellyfinMediaSource.continueWatching() } returns Result.success(emptyList())
    }

    @After fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun create() = MainViewModel(container).also { viewModel = it }

    @Test fun `download byte progress does not rescan completed entries but metadata changes do`() = runTest {
        val owner = DownloadOwner("server", "user")
        val identity = DownloadIdentity(owner, "completed")
        val progress = MutableStateFlow<Map<DownloadIdentity,
            DownloadState>>(emptyMap())
        val revision = MutableStateFlow(0L)
        val store = container.downloadStore
        every { container.authRepository.currentUser } returns MutableStateFlow(UserSession(owner.userId, owner.serverId))
        every { container.downloader.states } returns progress
        every { store.metadataRevision } returns revision
        coEvery { store.list(owner) } returns emptyList()
        create(); runCurrent()
        clearMocks(store, answers = false)
        val running = identity.copy(itemId = "running")
        repeat(5) {
            progress.value = mapOf(identity to DownloadState.Completed,
                running to DownloadState.Running(it.toLong(), 10))
            runCurrent()
        }
        coVerify(exactly = 0) { store.list(any<DownloadOwner>()) }
        revision.value++
        runCurrent()
        coVerify(exactly = 1) { store.list(owner) }
    }

    @Test fun `opening a video from search preserves the gallery query and results`() = runTest {
        val movie = local.copy(id = "movie-1", title = "Movie", sourceId = "remote")
        coEvery { container.jellyfinMediaSource.search("Movie") } returns Result.success(listOf(movie))
        coEvery { container.jellyfinMediaSource.item(movie.id) } returns Result.failure(IllegalStateException("Offline"))
        val vm = create()
        vm.onSearchQueryChanged("Movie")
        advanceUntilIdle()
        assertEquals(listOf(movie), vm.state.value.searchResults)

        vm.onItemClicked(movie)

        assertEquals(Route.MEDIA_DETAIL, vm.state.value.route)
        assertEquals("Movie", vm.state.value.searchQuery)
        assertEquals(listOf(movie), vm.state.value.searchResults)
    }

    @Test fun `opening a folder from search still leaves search results`() = runTest {
        val folder = local.copy(id = "folder-1", title = "Folder", isFolder = true, sourceId = "remote")
        coEvery { container.jellyfinMediaSource.search("Folder") } returns Result.success(listOf(folder))
        coEvery { container.jellyfinMediaSource.children(folder.id) } returns Result.success(emptyList())
        val vm = create()
        vm.onSearchQueryChanged("Folder")
        advanceUntilIdle()
        assertEquals(listOf(folder), vm.state.value.searchResults)

        vm.onItemClicked(folder)

        assertEquals(folder, vm.state.value.currentFolder)
        assertEquals("", vm.state.value.searchQuery)
        assertTrue(vm.state.value.searchResults.isEmpty())
    }

    @Test fun `queue can be built and saved without starting playback`() {
        val vm = create()
        vm.enqueue(local)
        vm.enqueue(local)
        vm.saveQueue("Evenings")
        val saved = vm.state.value.queues.saved.single()
        assertEquals(2, saved.contents.size)
        assertEquals(2, saved.contents.upNext.entries.map { it.entryId }.distinct().size)
        vm.newQueue()
        assertEquals(0, vm.state.value.queues.working.size)
        vm.loadQueue(saved.id)
        assertEquals(saved.contents, vm.state.value.queues.working)
        assertNull(vm.state.value.playback)
        verify(timeout = 3000) { container.savedQueueStore.write(match { it.activeId == saved.id && it.working.size == 2 }) }
    }

    @Test fun `restart and stop retain unfinished entry and allocate fresh ids`() {
        val entry = PlaylistEntry(7, local.copy(resumePositionSeconds = 125))
        every { container.savedQueueStore.read() } returns SavedQueueLibrary(QueueContents(current = entry)).save("q", "Later")
        val vm = create()
        vm.stopPlayback()
        assertEquals(entry, vm.state.value.queues.working.resumeEntry)
        vm.enqueue(local)
        assertEquals(8L, vm.state.value.playlist.next?.entryId)
        assertEquals(125L, vm.state.value.queues.saved.single().contents.current?.item?.resumePositionSeconds)
    }

    @Test fun `missing local item stays available for removal or retry`() {
        val entry = PlaylistEntry(1, local)
        every { container.savedQueueStore.read() } returns SavedQueueLibrary(QueueContents(current = entry))
        coEvery { container.localMediaSource.item(local.id) } returns Result.failure(IllegalStateException("Video is no longer available"))
        val vm = create()
        vm.resumeQueue()
        assertEquals(entry, vm.state.value.queues.working.resumeEntry)
        assertTrue(vm.state.value.errorMessage!!.contains("no longer available"))
        assertFalse(vm.state.value.isBusy)
        assertNotEquals(Route.TARGET_PICKER, vm.state.value.route)
        vm.removePlaylistEntry(1)
        assertEquals(0, vm.state.value.queues.working.size)
    }

    @Test fun `different account cannot resolve a persisted remote queue entry`() {
        val entry = PlaylistEntry(1, local.copy(id = "shared-id", sourceId = "remote"), UserSession("old", "server"))
        every { container.savedQueueStore.read() } returns SavedQueueLibrary(QueueContents(current = entry))
        val vm = create()
        vm.resumeQueue()
        coVerify(exactly = 0) { container.jellyfinMediaSource.item(any()) }
        assertEquals(entry, vm.state.value.queues.working.resumeEntry)
        assertTrue(vm.state.value.errorMessage!!.contains("Sign in"))
    }

    @Test fun `saving active playback checkpoints confirmed progress and natural completion finishes the queue`() {
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        val completions = MutableSharedFlow<PlaybackCompletion>(extraBufferCapacity = 4)
        every { container.playbackEngine.status } returns statuses
        every { container.playbackEngine.endOfMedia } returns completions
        every { container.playbackEngine.isCurrentPlaybackGeneration(1) } returns true
        every { container.permissions.hasLocalNetworkAccess() } returns true
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No remote source"))
        val vm = create()
        vm.onItemClicked(local)
        vm.selectPhysicalTv(PhysicalTv("tv", "TV", dlnaEndpoint = DiscoveredTarget(
            "tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
        )))
        val playing = PlaybackStatus(
            targetName = "TV", protocolName = "DLNA", rendererPlaybackRevision = 1,
            isPlaying = true, isBuffering = false, positionSeconds = 100, durationSeconds = 600,
            streamMode = "Direct", currentBitrateBps = 0, measuredThroughputBps = 0, adaptiveNote = "",
        )
        statuses.value = playing
        vm.saveQueue("Playing tonight")
        assertEquals(100L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        statuses.value = playing.copy(rendererPlaybackRevision = 2, positionSeconds = 125, recoveryPositionSeconds = 125)
        assertEquals(125L, vm.state.value.queues.saved.single().contents.current?.item?.resumePositionSeconds)
        // An optimistic/stale status with no new renderer observation must not overwrite the checkpoint.
        statuses.value = playing.copy(rendererPlaybackRevision = 2, positionSeconds = 500, recoveryPositionSeconds = 125)
        assertEquals(125L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        // Delivery can save a rewound estimate without claiming a new TV observation.
        statuses.value = playing.copy(rendererPlaybackRevision = 2, positionSeconds = 125, recoveryPositionSeconds = 159)
        assertEquals(159L, vm.state.value.queues.saved.single().contents.current?.item?.resumePositionSeconds)
        // A lower fresh TV report supersedes the estimate.
        statuses.value = playing.copy(rendererPlaybackRevision = 3, positionSeconds = 90, recoveryPositionSeconds = 90)
        assertEquals(90L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        assertTrue(completions.tryEmit(PlaybackCompletion(local, false, 1)))
        assertEquals(0, vm.state.value.queues.working.size)
        assertEquals(0, vm.state.value.queues.saved.single().contents.size)
        coVerify { container.playbackEngine.stop() }
    }

    @Test fun `cancelled renderer startup becomes actionable failure and explicit retry stays usable for every source`() {
        for (item in retrySources()) {
            val statuses = MutableStateFlow<PlaybackStatus?>(null)
            every { container.playbackEngine.status } returns statuses
            every { container.permissions.hasLocalNetworkAccess() } returns true
            every { container.localMediaSource.mimeTypeFor(any()) } returns "video/mp4"
            every { container.jellyfinWatchMutationStore.pendingFor(any(), any(), any()) } returns null
            every { container.smartResumeStore.current } returns null
            every { container.playbackEngine.recoverySnapshot() } returns PlaybackRecoverySession()
            every { container.playbackEngine.continuationForExplicitRetry() } returns null
            every { container.authRepository.currentUser } returns MutableStateFlow(UserSession("user", "server"))
            every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.ONLINE)
            coEvery { container.localMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
            coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
            coEvery { container.playbackEngine.stop() } answers { statuses.value = null }
            var attempts = 0
            coEvery { container.playbackEngine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                attempts++
                arg<() -> Unit>(10).invoke()
                throw CancellationException("Renderer dropped the startup")
            }
            coEvery { container.playbackEngine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                attempts++
                arg<() -> Unit>(8).invoke()
                throw CancellationException("Renderer dropped the startup")
            }
            val vm = create()
            vm.onItemClicked(item)
            vm.selectPhysicalTv(PhysicalTv("tv", "TV", dlnaEndpoint = DiscoveredTarget(
                "tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
            )))
            assertEquals(1, attempts, "Startup was not attempted for ${item.id}")
            assertTrue(vm.state.value.playback?.isTerminal == true, "Cancelled ${item.id} stayed Connecting")
            assertNull(vm.state.value.playbackStartingTargetId)
            vm.retryPlayback()
            assertEquals(2, attempts)
            assertTrue(vm.state.value.playback?.isTerminal == true, "Cancelled retry stayed Connecting")
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `cancelled Cast discovery cleanup does not prevent DLNA playback`() {
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        every { container.playbackEngine.status } returns statuses
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.localMediaSource.mimeTypeFor(any()) } returns "video/mp4"
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        coEvery { container.castController.stopDiscovery() } throws CancellationException("Discovery already ended")
        coEvery { container.playbackEngine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(10).invoke()
            statuses.value = statusesForRetry(Protocol.DLNA).copy(
                phase = PlaybackPhase.PLAYING, isPlaying = true, playWhenReady = true,
            )
        }
        val vm = create()
        vm.onItemClicked(local)
        vm.selectPhysicalTv(PhysicalTv("tv", "TV", dlnaEndpoint = DiscoveredTarget(
            "tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
        )))
        assertEquals(PlaybackPhase.PLAYING, vm.state.value.playback?.phase)
        assertNull(vm.state.value.playbackStartingTargetId)
    }

    @Test fun `same endpoint recovery resumes its own retry after phone LAN returns without another status event`() = runTest {
        val engine = container.playbackEngine
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        every { engine.status } returns statuses
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.localMediaSource.mimeTypeFor(any()) } returns "video/mp4"
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        every { engine.recoverySnapshot() } returns PlaybackRecoverySession(phase = PlaybackPhase.BUFFERING)
        var phoneIp: String? = "10.0.1.2"
        every { container.networkInfo.lanIpv4() } answers { phoneIp }
        val paused = statusesForRetry(Protocol.DLNA).copy(currentAudioIndex = 1, currentSubtitleIndex = 4)
        coEvery { engine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(10).invoke()
            statuses.value = paused
        }
        var retryCalls = 0
        coEvery { engine.retrySameEndpointAfterDisconnect(any()) } coAnswers {
            retryCalls++
            if (retryCalls == 1) {
                phoneIp = null
                statuses.value = paused.copy(
                    connectionLost = true, networkRecoveryDeferred = true,
                    phase = PlaybackPhase.BUFFERING, isBuffering = true,
                )
                false
            } else {
                statuses.value = paused
                true
            }
        }
        val vm = create()
        vm.onItemClicked(local)
        val dlna = DiscoveredTarget("tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null)
        vm.selectPhysicalTv(PhysicalTv("tv", "TV", dlnaEndpoint = dlna))
        assertEquals(PlaybackPhase.PAUSED, vm.state.value.playback?.phase)
        statuses.value = paused.copy(connectionLost = true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, retryCalls)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, retryCalls, "A phone-offline reconnect must wait without another endpoint attempt")
        verify(exactly = 0) { engine.reserveAlternateProtocolContinuation(any(), any(), any(), any(), any(), any()) }

        // Only connectivity changes. The deferred engine status remains identical until retry succeeds.
        phoneIp = "10.0.1.3"
        advanceTimeBy(500); runCurrent()

        assertEquals(2, retryCalls, "The active recovery owner must retry without a fresh disconnected status")
        coVerify(exactly = 2) { engine.retrySameEndpointAfterDisconnect(dlna) }
        coVerify(exactly = 1) { engine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { engine.reserveAlternateProtocolContinuation(any(), any(), any(), any(), any(), any()) }
        val recovered = assertNotNull(vm.state.value.playback)
        assertEquals(PlaybackPhase.PAUSED, recovered.phase)
        assertFalse(recovered.reconnecting)
        assertFalse(recovered.playWhenReady)
        assertEquals(125L, recovered.positionSeconds)
        assertEquals(1, recovered.currentAudioIndex)
        assertEquals(4, recovered.currentSubtitleIndex)
        assertEquals("hevc", recovered.preferredVideoCodec)
        assertEquals(480, recovered.maxVideoHeight)
    }

    @Test fun `cancelling the playback caller during discovery clears the picker without starting a stream`() = runTest {
        val playbackEngine = container.playbackEngine
        every { container.permissions.hasLocalNetworkAccess() } returns true
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        val stoppingDiscovery = CompletableDeferred<Unit>()
        coEvery { container.castController.stopDiscovery() } coAnswers {
            stoppingDiscovery.complete(Unit)
            awaitCancellation()
        }
        val vm = create()
        vm.onItemClicked(local)
        vm.prepareCastDownload("pending-download")
        vm.selectPhysicalTv(PhysicalTv("tv", "TV", dlnaEndpoint = DiscoveredTarget(
            "tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
        )))
        assertTrue(stoppingDiscovery.isCompleted)
        assertNotNull(vm.state.value.playbackStartingTargetId)
        vm.viewModelScope.cancel()
        runCurrent()
        assertNull(vm.state.value.playbackStartingTargetId)
        assertEquals("pending-download", vm.state.value.selectedDownloadId)
        assertNull(vm.state.value.playback)
        coVerify(exactly = 0) { playbackEngine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `manual protocol switch from Cast uses paired DLNA endpoint and confirmed position`() {
        assertManualProtocolSwitch(Protocol.CAST, Protocol.DLNA)
    }

    @Test fun `automatic Cast fallback discovers the same DLNA TV at startup and after failure for every source`() {
        for (item in retrySources()) for (startup in listOf(true, false)) {
            val fixture = automaticFallbackFixture(item, startup = startup)
            assertEquals(listOf(Protocol.CAST, Protocol.DLNA), fixture.loads.map { it.first })
            assertEquals(125L, fixture.loads.last().second)
            assertSame(fixture.continuation, fixture.loads.last().third)
            assertEquals(Protocol.DLNA, fixture.vm.state.value.selectedTarget?.protocol)
            assertNotNull(fixture.vm.state.value.selectedPhysicalTv?.castEndpoint)
            assertNotNull(fixture.vm.state.value.selectedPhysicalTv?.dlnaEndpoint)
            assertEquals(PlaybackPhase.PAUSED, fixture.vm.state.value.playback?.phase)
            assertEquals(LocalTranscodeMode.ON_DEVICE, fixture.vm.state.value.playback?.localTranscodeMode)
            assertEquals("hevc", fixture.vm.state.value.playback?.preferredVideoCodec)
            assertEquals(480, fixture.vm.state.value.playback?.maxVideoHeight)
            fixture.vm.viewModelScope.cancel()
        }
    }

    @Test fun `automatic Cast fallback refuses unrelated ambiguous and explicitly unlinked DLNA endpoints`() {
        for (rejection in listOf("unrelated", "ambiguous", "unlinked")) {
            val fixture = automaticFallbackFixture(rejection = rejection)
            assertEquals(listOf(Protocol.CAST), fixture.loads.map { it.first }, rejection)
            assertTrue(fixture.vm.state.value.playback?.isTerminal == true, rejection)
            fixture.vm.viewModelScope.cancel()
        }
    }

    @Test fun `failed DLNA fallback discovery remains actionable and Stop prevents a late handoff`() {
        val failed = automaticFallbackFixture(discoveryError = CancellationException("Discovery interrupted"))
        assertEquals(listOf(Protocol.CAST), failed.loads.map { it.first })
        assertTrue(failed.vm.state.value.playback?.isTerminal == true)
        failed.vm.viewModelScope.cancel()
        val stopped = automaticFallbackFixture(stopDuringDiscovery = true)
        assertEquals(listOf(Protocol.CAST), stopped.loads.map { it.first })
        assertEquals(Route.GALLERY, stopped.vm.state.value.route)
        assertNull(stopped.vm.state.value.playback)
    }

    @Test fun `terminal network recovery reports connection failure instead of exhausted formats for every source`() {
        for (item in retrySources()) {
            val fixture = automaticFallbackFixture(item, startup = false, rejection = "unrelated",
                failureCause = PlaybackFailureCause.TRANSIENT_NETWORK)
            val failed = assertNotNull(fixture.vm.state.value.playback)
            assertTrue(failed.isTerminal)
            assertEquals("The phone couldn't reach the TV. Check both devices' network connections, then retry.",
                failed.errorMessage)
            fixture.vm.viewModelScope.cancel()
        }
    }

    private data class AutomaticFallbackFixture(
        val vm: MainViewModel,
        val continuation: PlaybackRecoveryContinuation,
        val loads: List<Triple<Protocol, Long, PlaybackRecoveryContinuation?>>,
    )

    private fun automaticFallbackFixture(
        item: MediaItem = local,
        startup: Boolean = true,
        rejection: String? = null,
        discoveryError: Throwable? = null,
        stopDuringDiscovery: Boolean = false,
        failureCause: PlaybackFailureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
    ): AutomaticFallbackFixture {
        val engine = container.playbackEngine
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        every { engine.status } returns statuses
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.localMediaSource.mimeTypeFor(any()) } returns "video/mp4"
        every { container.jellyfinWatchMutationStore.pendingFor(any(), any(), any()) } returns null
        every { container.smartResumeStore.current } returns null
        every { container.authRepository.currentUser } returns MutableStateFlow(UserSession("user", "server"))
        every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.ONLINE)
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        coEvery { container.localMediaSource.item(any()) } returns Result.failure(IllegalStateException("No details"))
        every { container.physicalTvAssociations.isLinked(any(), any()) } returns false
        every { container.physicalTvAssociations.isBlocked(any(), any()) } returns (rejection == "unlinked")
        every { container.physicalTvAssociations.linkedPeer(any()) } returns null
        every { container.physicalTvAssociations.selectionFor(any()) } returns EndpointSelectionPreference()
        val attempt = PlaybackAttemptDescriptor(
            generation = 1, protocol = Protocol.CAST.name,
            failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION,
            failureCause = failureCause,
        )
        every { engine.recoverySnapshot() } returns PlaybackRecoverySession(
            phase = PlaybackPhase.FAILED, attempts = listOf(attempt),
            usage = RecoveryBudgetUsage(automaticAttempts = 2, endpointConnectionRetries = mapOf(Protocol.CAST to 2)),
        )
        val continuation = mockk<PlaybackRecoveryContinuation>(relaxed = true)
        every { continuation.positionSeconds } returns 125L
        every { engine.reserveAlternateProtocolContinuation(any(), any(), any(), any(), any(), any()) } returns continuation
        coEvery { engine.stop() } answers { statuses.value = null }
        val cast = DiscoveredTarget("cast-route", "TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null,
            discoveryMetadata = TargetDiscoveryMetadata(
                castDeviceId = "cast-id", validatedSourceHost = "10.0.1.44", modelName = "OLED55C4PLA",
            ))
        val dlna = DiscoveredTarget("uuid:dlna", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
            discoveryMetadata = TargetDiscoveryMetadata(
                dlnaUdn = "uuid:dlna", validatedSourceHost = if (rejection == "unrelated") "10.0.1.99" else "10.0.1.44",
                modelName = "OLED55C4PLA",
            ))
        val candidates = if (rejection == "ambiguous") listOf(dlna, dlna.copy(
            id = "uuid:other", discoveryMetadata = dlna.discoveryMetadata.copy(dlnaUdn = "uuid:other"),
        )) else listOf(dlna)
        lateinit var vm: MainViewModel
        coEvery { container.dlnaController.discover(any()) } coAnswers {
            if (stopDuringDiscovery) vm.stopPlayback()
            discoveryError?.let { throw it }
            candidates
        }
        val loads = mutableListOf<Triple<Protocol, Long, PlaybackRecoveryContinuation?>>()
        fun load(endpoint: DiscoveredTarget, position: Long, choices: PlaybackRecoveryContinuation?, foregrounded: () -> Unit) {
            loads += Triple(endpoint.protocol, position, choices)
            foregrounded()
            if (endpoint.protocol == Protocol.CAST) {
                if (startup) throw IllegalStateException("Cast could not connect")
            } else statuses.value = statusesForRetry(Protocol.DLNA)
        }
        coEvery { engine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            load(arg(9), arg(5), arg(11), arg(10))
        }
        coEvery { engine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            load(arg(2), arg<Long?>(4) ?: 0L, arg(9), arg(8))
        }
        vm = create()
        vm.onItemClicked(item)
        vm.selectPhysicalTv(PhysicalTv("cast-only", "TV", castEndpoint = cast))
        if (!startup) {
            statuses.value = statusesForRetry(Protocol.CAST)
            statuses.value = statusesForRetry(Protocol.CAST).copy(
                phase = PlaybackPhase.FAILED, isTerminal = true, attemptHistory = listOf(attempt),
            )
        }
        return AutomaticFallbackFixture(vm, continuation, loads)
    }

    @Test fun `manual protocol switch from DLNA uses paired Cast endpoint and confirmed position`() {
        assertManualProtocolSwitch(Protocol.DLNA, Protocol.CAST)
    }

    @Test fun `failed Cast to DLNA switch retains choices and latest checkpoint through repeated explicit retries`() {
        retrySources().forEach { assertFailedSwitchRetry(Protocol.CAST, it) }
    }

    @Test fun `failed DLNA to Cast switch retains choices and latest checkpoint through repeated explicit retries`() {
        retrySources().forEach { assertFailedSwitchRetry(Protocol.DLNA, it) }
    }

    @Test fun `Stop and a new video discard failed switch retry choices`() {
        for (stop in listOf(true, false)) {
            val fixture = failedSwitchFixture(Protocol.CAST)
            val vm = fixture.vm
            if (stop) {
                vm.stopPlayback()
                val callsBeforeRetry = fixture.loads.size
                vm.retryPlayback()
                assertEquals(callsBeforeRetry, fixture.loads.size)
            } else {
                every { container.playbackEngine.continuationForExplicitRetry() } returns null
                vm.onItemClicked(local.copy(id = "content://media/video/2", title = "Next video"))
                vm.play()
                vm.retryPlayback()
                assertNull(fixture.loads.last().second)
            }
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `unrelated account change retains local failed switch retry choices`() {
        for (protocol in Protocol.entries) {
            val fixture = failedSwitchFixture(protocol)
            fixture.users.value = UserSession("another-user", "another-server")
            fixture.vm.retryPlayback()
            assertEquals(125L to fixture.firstRetry, fixture.loads.last())
            fixture.vm.viewModelScope.cancel()
        }
    }

    @Test fun `online failed switch retry cannot adopt another account`() {
        val fixture = failedSwitchFixture(Protocol.CAST, retrySources().last())
        val attempts = fixture.loads.size
        fixture.users.value = UserSession("another-user", "another-server")
        fixture.vm.retryPlayback()
        assertEquals(attempts, fixture.loads.size)
    }

    @Test fun `manual watch state override suppresses retry checkpoint updates`() {
        val fixture = failedSwitchFixture(Protocol.CAST)
        val previous = fixture.vm.state.value.queues.working.current?.item?.resumePositionSeconds
        every { container.playbackEngine.hasManualWatchStateOverride() } returns true
        coEvery { container.playbackEngine.stop() } answers {
            fixture.statuses.value = null
        }
        fixture.vm.retryPlayback()
        assertEquals(previous, fixture.vm.state.value.queues.working.current?.item?.resumePositionSeconds)
    }

    private data class FailedSwitchFixture(
        val vm: MainViewModel,
        val statuses: MutableStateFlow<PlaybackStatus?>,
        val loads: MutableList<Pair<Long, PlaybackRecoveryContinuation?>>,
        val firstRetry: PlaybackRecoveryContinuation,
        val secondRetry: PlaybackRecoveryContinuation,
        val users: MutableStateFlow<UserSession?>,
    )

    private fun retrySources() = listOf(
        local,
        local.copy(id = "/storage/emulated/0/Movies/video.mp4"),
        local.copy(id = "remote-video", sourceId = "remote"),
    )

    private fun assertFailedSwitchRetry(from: Protocol, item: MediaItem) {
        val fixture = failedSwitchFixture(from, item)
        val vm = fixture.vm
        val failed = assertNotNull(vm.state.value.playback, "Failed handoff disappeared for $from/${item.sourceId}/${item.id}")
        assertTrue(failed.isTerminal, "Expected failed switch for $from/${item.sourceId}/${item.id}: phase=${failed.phase}, loads=${fixture.loads}, error=${vm.state.value.errorMessage}")
        assertFalse(failed.playWhenReady)
        assertEquals(LocalTranscodeMode.ON_DEVICE, failed.localTranscodeMode)
        assertEquals("hevc", failed.preferredVideoCodec)
        assertEquals(480, failed.maxVideoHeight)
        // stop() has discarded the engine's live state; Retry must use the captured continuation.
        assertNull(fixture.statuses.value)
        coVerify { container.playbackEngine.stop() }

        vm.retryPlayback()
        assertEquals(125L to fixture.firstRetry, fixture.loads.last())
        assertTrue(vm.state.value.playback?.isTerminal == true)
        vm.retryPlayback()
        assertEquals(159L to fixture.secondRetry, fixture.loads.last())
        assertEquals(159L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        vm.viewModelScope.cancel()
    }

    private fun failedSwitchFixture(from: Protocol, item: MediaItem = local): FailedSwitchFixture {
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        val playbackEngine = container.playbackEngine
        every { playbackEngine.status } returns statuses
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.localMediaSource.mimeTypeFor(any()) } returns "video/mp4"
        every { container.jellyfinWatchMutationStore.pendingFor(any(), any(), any()) } returns null
        every { container.smartResumeStore.current } returns null
        coEvery { container.localMediaSource.item(any()) } returns Result.failure(IllegalStateException("No detail refresh"))
        coEvery { container.jellyfinMediaSource.item(any()) } returns Result.failure(IllegalStateException("No detail refresh"))
        val owner = UserSession("user", "server")
        val users = MutableStateFlow(owner.takeIf { item.sourceId == "remote" })
        every { container.authRepository.currentUser } returns users
        every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.ONLINE)
        every { playbackEngine.recoverySnapshot() } returns PlaybackRecoverySession()
        val manual = mockk<PlaybackRecoveryContinuation>(relaxed = true)
        val firstRetry = mockk<PlaybackRecoveryContinuation>(relaxed = true)
        val secondRetry = mockk<PlaybackRecoveryContinuation>(relaxed = true)
        every { playbackEngine.beginManualProtocolSwitch() } returns manual
        every { manual.positionSeconds } returns 125L
        every { firstRetry.positionSeconds } returns 125L
        every { secondRetry.positionSeconds } returns 159L
        every { playbackEngine.continuationForExplicitRetry() } returnsMany listOf(firstRetry, secondRetry)
        coEvery { playbackEngine.stop() } answers { statuses.value = null }
        val loads = mutableListOf<Pair<Long, PlaybackRecoveryContinuation?>>()
        fun recordLoad(resume: Long, continuation: PlaybackRecoveryContinuation?) {
            loads += resume to continuation
            when (continuation) {
                manual -> throw IllegalStateException("Replacement stream failed")
                firstRetry -> {
                    statuses.value = statusesForRetry(if (from == Protocol.CAST) Protocol.DLNA else Protocol.CAST).copy(
                        rendererPlaybackRevision = 2,
                        positionSeconds = 159,
                        recoveryPositionSeconds = 159,
                    )
                    throw IllegalStateException("Retry stream failed")
                }
                else -> Unit
            }
        }
        coEvery {
            playbackEngine.playLocal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())
        } coAnswers { recordLoad(arg(5), arg(11)) }
        coEvery {
            playbackEngine.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers { recordLoad(arg<Long?>(4) ?: 0L, arg(9)) }
        val cast = DiscoveredTarget("cast-tv", "TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null)
        val dlna = DiscoveredTarget("dlna-tv", "TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null)
        val tv = PhysicalTv("tv", "TV", castEndpoint = cast, dlnaEndpoint = dlna,
            selectionPreference = EndpointSelectionPreference(preferredProtocol = from))
        val vm = create()
        vm.onItemClicked(item)
        vm.selectPhysicalTv(tv)
        assertTrue(loads.isNotEmpty(), "Initial playback did not start for $from/${item.sourceId}/${item.id}: ${vm.state.value.playback?.errorMessage}")
        statuses.value = statusesForRetry(from)
        vm.saveQueue("Protocol switch")
        assertEquals(125L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        vm.selectPlaybackProtocol(if (from == Protocol.CAST) Protocol.DLNA else Protocol.CAST)
        return FailedSwitchFixture(vm, statuses, loads, firstRetry, secondRetry, users)
    }

    private fun statusesForRetry(protocol: Protocol) = PlaybackStatus(
        targetName = "TV", protocolName = protocol.name, rendererPlaybackRevision = 1,
        isPlaying = false, playWhenReady = false, isBuffering = false,
        positionSeconds = 125, durationSeconds = 600, streamMode = "Transcode",
        currentBitrateBps = 2_000_000, measuredThroughputBps = 0, adaptiveNote = "",
        phase = PlaybackPhase.PAUSED, localTranscodeMode = LocalTranscodeMode.ON_DEVICE,
        preferredVideoCodec = "hevc", maxVideoHeight = 480,
    )

    private fun assertManualProtocolSwitch(from: Protocol, to: Protocol) {
        val statuses = MutableStateFlow<PlaybackStatus?>(null)
        val playbackEngine = container.playbackEngine
        every { playbackEngine.status } returns statuses
        every { container.permissions.hasLocalNetworkAccess() } returns true
        every { container.localMediaSource.mimeTypeFor(local.id) } returns "video/mp4"
        val continuation = mockk<PlaybackRecoveryContinuation>(relaxed = true)
        every { continuation.positionSeconds } returns 125L
        every { playbackEngine.beginManualProtocolSwitch() } returns continuation
        val cast = DiscoveredTarget(
            "cast-tv", "Living Room TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null,
        )
        val dlna = DiscoveredTarget(
            "dlna-tv", "Living Room TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null,
        )
        val tv = PhysicalTv("living-room", "Living Room TV", castEndpoint = cast, dlnaEndpoint = dlna,
            selectionPreference = EndpointSelectionPreference(preferredProtocol = from))
        val destination = if (to == Protocol.CAST) cast else dlna
        val vm = create()

        vm.onItemClicked(local)
        vm.selectPhysicalTv(tv)
        statuses.value = PlaybackStatus(
            targetName = tv.displayName,
            protocolName = from.name,
            rendererPlaybackRevision = 1,
            isPlaying = true,
            isBuffering = false,
            positionSeconds = 125,
            durationSeconds = local.runtimeSeconds,
            streamMode = "Direct",
            currentBitrateBps = 0,
            measuredThroughputBps = 0,
            adaptiveNote = "",
            phase = PlaybackPhase.PLAYING,
        )

        vm.selectPlaybackProtocol(from)
        verify(exactly = 0) { playbackEngine.beginManualProtocolSwitch() }
        vm.selectPlaybackProtocol(to)

        assertEquals(to, vm.state.value.selectedTarget?.protocol)
        coVerify(exactly = 1) {
            playbackEngine.playLocal(
                filePath = local.id,
                contentType = "video/mp4",
                title = local.title,
                runtimeSeconds = local.runtimeSeconds,
                allowClientTranscode = any(),
                resumePositionSeconds = 125,
                smartResumeSeed = any(),
                smartResumeDeviceContext = match { it.lastSuccessfulProtocol == to },
                target = any(),
                selectedTarget = destination,
                onForegrounded = any(),
                recoveryContinuation = continuation,
                preserveManualWatchOverride = true,
                prefs = any(),
            )
        }
    }
}
