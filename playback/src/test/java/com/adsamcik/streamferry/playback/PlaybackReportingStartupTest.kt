package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.source.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportingStartupTest {
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
