package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.resume.ProgressEstimate
import com.adsamcik.streamferry.core.resume.ProgressConfidence
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.source.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportingStartupTest {
    @Test fun `estimated checkpoint freezes on expiry and is replaced by fresh TV truth or seek`() = runTest {
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:1234"
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8)
        val coordinator = DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, mockk(relaxed = true), StandardTestDispatcher(testScheduler))
        coordinator.startAndBuildUrl(source, "127.0.0.1", 100)
        runCurrent()
        val token = coordinator.activeReportToken()!!
        val estimate = ProgressEstimate(160, 100, 60_000, 6, ProgressConfidence.DELIVERY)
        coordinator.noteEstimate(estimate, token + 1)
        coordinator.reportProgress(false)
        coVerify { source.report(PlaybackEvent.Progress(100, false)) }
        coordinator.noteEstimate(estimate, token)
        coordinator.noteEstimate(null, token)
        coordinator.noteEstimate(estimate.copy(anchorAgeMillis = 180_000), token)
        coordinator.reportProgress(true)
        coVerify { source.report(PlaybackEvent.Progress(100, true, estimate)) }
        coordinator.notePosition(90, token)
        coordinator.noteEstimate(estimate, token)
        coordinator.reportProgress(false)
        coVerify { source.report(PlaybackEvent.Progress(90, false)) }
        coordinator.noteEstimate(estimate.copy(anchorPositionSeconds = 90), token)
        coordinator.clearEstimate(token)
        coordinator.reportProgress(false)
        coVerify(exactly = 2) { source.report(PlaybackEvent.Progress(90, false)) }
        coordinator.notePosition(100, token)
        coordinator.noteEstimate(estimate, token)
        coordinator.stop("connection lost")
        coVerify { source.report(PlaybackEvent.Stopped(100, estimate)) }
    }

    @Test fun `manual edit drains in flight reports and suppresses later progress and stop but keeps cleanup`() = runTest {
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:1234"
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8)
        val coordinator = DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, mockk(relaxed = true), StandardTestDispatcher(testScheduler))
        var automatic = true
        val progressStarted = CompletableDeferred<Unit>()
        val releaseProgress = CompletableDeferred<Unit>()
        coEvery { source.report(any()) } coAnswers {
            if (firstArg<PlaybackEvent>() is PlaybackEvent.Progress) {
                progressStarted.complete(Unit)
                releaseProgress.await()
            }
        }
        coordinator.startAndBuildUrl(source, "127.0.0.1", shouldReport = { automatic })
        runCurrent()
        val progress = launch { coordinator.reportProgress(false) }
        progressStarted.await()
        automatic = false
        val manualWrite = launch { coordinator.awaitPendingReports() }
        runCurrent()
        assertFalse(manualWrite.isCompleted)
        releaseProgress.complete(Unit)
        progress.join(); manualWrite.join()
        coordinator.notePosition(1150)
        coordinator.reportProgress(false)
        coordinator.stop("finished")
        coVerify(exactly = 1) { source.report(any<PlaybackEvent.Progress>()) }
        coVerify(exactly = 0) { source.report(any<PlaybackEvent.Stopped>()) }
        coVerify(exactly = 1) { source.close(any()) }
        verify { proxy.stop() }
        coordinator.startAndBuildUrl(source, "127.0.0.1")
        runCurrent()
        coordinator.reportProgress(false)
        coordinator.stop("next finished")
        coVerify(exactly = 2) { source.report(any<PlaybackEvent.Started>()) }
        coVerify(exactly = 2) { source.report(any<PlaybackEvent.Progress>()) }
        coVerify(exactly = 1) { source.report(any<PlaybackEvent.Stopped>()) }
    }

    @Test fun `suppression before started runs skips all lifecycle reports`() = runTest {
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:1234"
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8)
        val coordinator = DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, mockk(relaxed = true), StandardTestDispatcher(testScheduler))
        var automatic = true
        coordinator.startAndBuildUrl(source, "127.0.0.1", shouldReport = { automatic })
        automatic = false
        runCurrent()
        coordinator.awaitPendingReports()
        coordinator.reportProgress(false)
        coordinator.stop("manual state")
        coVerify(exactly = 0) { source.report(any()) }
        coVerify(exactly = 1) { source.close(any()) }
    }

    @Test fun `slow started report does not block URL and stop revokes before ordered reporting`() = runTest {
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:1234"
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor(
            "video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8,
        )
        val started = CompletableDeferred<Unit>()
        val reports = mutableListOf<PlaybackEvent>()
        coEvery { source.report(any()) } coAnswers {
            val event = firstArg<PlaybackEvent>()
            if (event is PlaybackEvent.Started) started.await()
            reports += event
        }
        val coordinator = DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, mockk(relaxed = true), StandardTestDispatcher(testScheduler))
        val (_, url) = coordinator.startAndBuildUrl(source, "127.0.0.1", 30)
        assertTrue(url.endsWith("/stream"))
        runCurrent()
        val stop = launch { coordinator.stop("user stopped") }; runCurrent()
        verify { proxy.stop() }
        assertNull(coordinator.active.value)
        assertFalse(stop.isCompleted)
        assertTrue(reports.isEmpty())
        started.complete(Unit); stop.join()
        assertEquals(listOf(PlaybackEvent.Started(30), PlaybackEvent.Stopped(30)), reports)
    }

    @Test fun `hung started report times out and progress proceeds in order`() = runTest {
        val proxy = mockk<LocalProxyServer>(relaxed = true)
        every { proxy.start(any()) } returns "127.0.0.1:1234"
        val source = mockk<ProviderPlaybackSession>(relaxed = true)
        every { source.descriptor.stream } returns StreamDescriptor(
            "video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8,
        )
        coEvery { source.report(any()) } coAnswers {
            if (firstArg<PlaybackEvent>() is PlaybackEvent.Started) awaitCancellation()
        }
        val coordinator = DefaultPlaybackSessionCoordinator(SessionRegistry(), proxy, mockk(relaxed = true), StandardTestDispatcher(testScheduler))
        coordinator.startAndBuildUrl(source, "127.0.0.1", 30)
        val progress = launch { coordinator.reportProgress(false) }; runCurrent()
        assertFalse(progress.isCompleted)
        advanceTimeBy(3000); runCurrent()
        assertTrue(progress.isCompleted)
        coVerify { source.report(PlaybackEvent.Progress(30, false)) }
        coordinator.stop("user stopped")
    }
}
