package com.adsamcik.streamferry.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.adsamcik.streamferry.domain.SourceAvailability
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackStatus
import com.adsamcik.streamferry.playback.SavedQueueLibrary
import com.adsamcik.streamferry.ui.state.PlaybackControlKind
import com.adsamcik.streamferry.ui.state.displayedVolume
import com.adsamcik.streamferry.ui.theme.ThemeMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainViewModelVolumeTest {
    private val container = mockk<AppContainer>(relaxed = true)
    private val statuses = MutableStateFlow<PlaybackStatus?>(null)
    private lateinit var viewModel: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { container.savedQueueStore.read() } returns SavedQueueLibrary()
        every { container.appearancePreferences.themeMode } returns ThemeMode.SYSTEM
        every { container.permissions.localNetworkAccess } returns MutableStateFlow(false)
        every { container.playbackEngine.status } returns statuses
        every { container.playbackEngine.endOfMedia } returns MutableSharedFlow()
        every { container.authRepository.currentUser } returns MutableStateFlow(null)
        every { container.authRepository.cachedSession } returns MutableStateFlow(null)
        every { container.jellyfinConnectionMonitor.status } returns MutableStateFlow(SourceAvailability.UNKNOWN)
        every { container.smartResumeStore.history } returns MutableStateFlow(emptyList())
        every { container.downloader.states } returns MutableStateFlow(emptyMap())
        coEvery { container.authRepository.restoreCachedSession() } returns null
        coEvery { container.authRepository.ensureOnlineSession() } returns Result.failure(IllegalStateException("Offline"))
        viewModel = MainViewModel(container)
        statuses.value = PlaybackStatus(
            targetName = "TV", protocolName = "DLNA", isPlaying = true, isBuffering = false,
            positionSeconds = 0, durationSeconds = 600, streamMode = "Direct", currentBitrateBps = 0,
            measuredThroughputBps = 0, adaptiveNote = "", volume = 0.5f, volumeSupported = true,
            attemptGeneration = 1, phase = PlaybackPhase.PLAYING,
        )
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `drag sends immediately and only the latest queued value follows a slow command`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        var inFlight = 0
        var maximumInFlight = 0
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            inFlight++
            maximumInFlight = maxOf(maximumInFlight, inFlight)
            if (sent.size == 1) firstResponse.await()
            inFlight--
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.3f)
        viewModel.setVolume(0.4f)
        viewModel.setVolume(0.6f)

        assertEquals(listOf(0.2f), sent)
        assertEquals(0.6f, assertNotNull(viewModel.state.value.playback).displayedVolume)
        firstResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(0.2f, 0.6f), sent)
        assertEquals(1, maximumInFlight)
    }

    @Test fun `failure of an older command still sends the newest value without a stale error`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            if (sent.size == 1) {
                firstResponse.await()
                error("TV rejected the older value")
            }
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.7f)
        firstResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(0.2f, 0.7f), sent)
        assertNull(assertNotNull(viewModel.state.value.playback).controls.issue)
    }

    @Test fun `slow commands cannot expire the latest intent before its failure is reported`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val finalResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            if (sent.size == 1) firstResponse.await() else {
                finalResponse.await()
                error("TV rejected the final value")
            }
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.7f)
        statuses.value = assertNotNull(statuses.value).copy(volume = 0.2f, rendererVolumeRevision = 1)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(0.7f, assertNotNull(viewModel.state.value.playback).displayedVolume)
        assertNotNull(assertNotNull(viewModel.state.value.playback).controls.volume)

        firstResponse.complete(Unit)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(0.2f, 0.7f), sent)
        assertEquals(0.7f, assertNotNull(viewModel.state.value.playback).displayedVolume)
        assertNotNull(assertNotNull(viewModel.state.value.playback).controls.volume)

        finalResponse.complete(Unit)
        runCurrent()
        val failed = assertNotNull(viewModel.state.value.playback).controls
        assertEquals(PlaybackControlKind.VOLUME, assertNotNull(failed.issue).kind)
        assertNull(failed.volume)
    }

    @Test fun `latest failure is visible and the next adjustment can restart the sender`() = runTest {
        coEvery { container.playbackEngine.setVolume(any()) } throws IllegalStateException("TV unavailable")
        viewModel.setVolume(0.2f)
        val failed = assertNotNull(viewModel.state.value.playback).controls
        assertEquals(PlaybackControlKind.VOLUME, assertNotNull(failed.issue).kind)
        assertNull(failed.volume)

        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers { sent += firstArg<Float>() }
        viewModel.setVolume(0.8f)

        assertEquals(listOf(0.8f), sent)
        assertNull(assertNotNull(viewModel.state.value.playback).controls.issue)
        assertEquals(0.8f, assertNotNull(viewModel.state.value.playback).displayedVolume)
    }

    @Test fun `queued adjustment is discarded when the playback session changes`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            if (sent.size == 1) firstResponse.await()
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.7f)
        statuses.value = assertNotNull(statuses.value).copy(attemptGeneration = 2)
        firstResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(0.2f), sent)
        assertNull(assertNotNull(viewModel.state.value.playback).controls.volume)
        viewModel.setVolume(0.8f)
        assertEquals(listOf(0.2f, 0.8f), sent)
    }

    @Test fun `stopping playback drops the queued value`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            if (sent.size == 1) firstResponse.await()
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.7f)
        viewModel.stopPlayback()
        firstResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(0.2f), sent)
    }

    @Test fun `clearing the ViewModel cancels the sender and drops queued values`() = runTest {
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<Float>()
        coEvery { container.playbackEngine.setVolume(any()) } coAnswers {
            sent += firstArg<Float>()
            firstResponse.await()
        }

        viewModel.setVolume(0.2f)
        viewModel.setVolume(0.7f)
        viewModel.viewModelScope.cancel()
        firstResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(0.2f), sent)
        assertNull(assertNotNull(viewModel.state.value.playback).controls.issue)
    }
}
