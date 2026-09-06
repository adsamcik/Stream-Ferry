package com.adsamcik.streamferry.app

import android.app.Application
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.SourceAvailability
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.*
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.ThemeMode
import io.mockk.*
import kotlinx.coroutines.Dispatchers
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
        every { container.resumeStore.resumePosition(any()) } returns null
        coEvery { container.authRepository.restoreCachedSession() } returns null
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.failure(IllegalStateException("Offline"))
    }

    @After fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun create() = MainViewModel(container).also { viewModel = it }

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
        statuses.value = playing.copy(rendererPlaybackRevision = 2, positionSeconds = 125)
        assertEquals(125L, vm.state.value.queues.saved.single().contents.current?.item?.resumePositionSeconds)
        // An optimistic/stale status with no new renderer observation must not overwrite the checkpoint.
        statuses.value = playing.copy(rendererPlaybackRevision = 2, positionSeconds = 500)
        assertEquals(125L, vm.state.value.queues.working.current?.item?.resumePositionSeconds)
        assertTrue(completions.tryEmit(PlaybackCompletion(local, false, 1)))
        assertEquals(0, vm.state.value.queues.working.size)
        assertEquals(0, vm.state.value.queues.saved.single().contents.size)
        coVerify { container.playbackEngine.stop() }
    }
}
