package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.stream.*
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.domain.*
import com.adsamcik.streamferry.source.api.PlaybackProvider
import com.adsamcik.streamferry.source.api.PlaybackRequest
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import com.adsamcik.streamferry.source.api.PlaybackDescriptor
import com.adsamcik.streamferry.source.api.StreamDescriptor
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartPath
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineIncidentTest {
    private class Fixture(scope: TestScope) {
        val events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
        val target = mockk<PlaybackTargetController>(relaxed = true)
        val provider = mockk<PlaybackProvider>()
        val logger = mockk<DiagnosticSink>(relaxed = true)
        val coordinator = mockk<DefaultPlaybackSessionCoordinator>(relaxed = true)
        val service = mockk<PlaybackServiceController>(relaxed = true)
        val movie = MediaItem("movie", "Movie", null, 3600, null, null, false)
        val selected = DiscoveredTarget("tv", "TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null)
        val engine: PlaybackEngine
        init {
            every { target.events } returns events
            every { target.protocol } returns Protocol.CAST
            coEvery { target.readCurrentVolume() } returns 0.1f
            coEvery { provider.prepare(any()) } answers {
                val request = firstArg<PlaybackRequest>()
                val stream = StreamDescriptor(
                    "video/mp4", "mp4", isTranscoding = request.forceTranscode,
                    isByteSeekable = !request.forceTranscode, totalLength = 100_000L,
                    hlsSegmentFormat = if (request.forceTranscode) com.adsamcik.streamferry.source.api.HlsSegmentFormat.MPEG2_TS else null,
                )
                val source = mockk<ProviderPlaybackSession>(relaxed = true)
                every { source.descriptor } returns PlaybackDescriptor(
                    movie.ref, MediaProfile("mp4", "h264", audioCodec = "aac"), 3600, 2_000_000, stream = stream,
                )
                coEvery { source.mediaSegments() } returns emptyList()
                Result.success(source)
            }
            coEvery { coordinator.startAndBuildUrl(any(), any(), any()) } returns
                (SessionRegistry().create("video/mp4", false, 100_000) to "http://127.0.0.1/session/test/stream")
            every { service.isForegroundActive() } returns true
            coEvery { service.startAndAwaitForegrounded(any()) } returns
                ForegroundServiceStartResult.Foregrounded(ProxyForegroundStartPath.START_SERVICE)
            val network = mockk<com.adsamcik.streamferry.diagnostics.NetworkInfoProvider>()
            every { network.lanIpv4() } returns "127.0.0.1"
            engine = PlaybackEngine(
                provider, coordinator, mockk(relaxed = true), network, service, logger,
                mockk(relaxed = true), mockk(relaxed = true), { mockk(relaxed = true) },
                mockk(relaxed = true), clock = { scope.testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(scope.testScheduler),
            )
        }
        suspend fun start() = engine.play(movie, target, selected, StreamPreferences())
        fun emit(event: PlaybackTargetEvent) { check(events.tryEmit(event)) }
    }

    @Test fun `successful fallback still accepts real end of media`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.emit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "Unsupported")); runCurrent()
            coVerify(exactly = 2) { f.provider.prepare(any()) }
            f.emit(PlaybackTargetEvent.StatusChanged(1, true)); runCurrent()
            f.emit(PlaybackTargetEvent.Ended); runCurrent()
            verify { f.logger.event("playback", match { it.startsWith("End of media reached") }) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `fallback does not suppress a later genuine playback failure`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.emit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "Unsupported")); runCurrent()
            f.emit(PlaybackTargetEvent.StatusChanged(1, true)); runCurrent()
            f.emit(PlaybackTargetEvent.Error(PlaybackFailureKind.NETWORK, "Network lost")); runCurrent()
            coVerify(exactly = 3) { f.provider.prepare(any()) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `new seek status before command completion replaces prior seek guard`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.engine.seekTo(30)
            val command = CompletableDeferred<Unit>()
            coEvery { f.target.seekTo(60) } coAnswers { command.await() }
            val seek = launch { f.engine.seekTo(60) }; runCurrent()
            f.emit(PlaybackTargetEvent.StatusChanged(60, true)); runCurrent()
            assertEquals(60L, f.engine.status.value?.positionSeconds)
            assertEquals(true, f.engine.status.value?.isPlaying)
            command.complete(Unit); seek.join()
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `failed seek keeps last confirmed position`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.engine.seekTo(30)
            coEvery { f.target.seekTo(60) } throws IllegalStateException("Rejected")
            assertFailsWith<IllegalStateException> { f.engine.seekTo(60) }
            assertEquals(30L, f.engine.status.value?.positionSeconds)
            f.emit(PlaybackTargetEvent.StatusChanged(30, true)); runCurrent()
            assertEquals(true, f.engine.status.value?.isPlaying)
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `lost foreground service is restored without reloading the TV stream`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.emit(PlaybackTargetEvent.StatusChanged(1, true)); runCurrent()
            every { f.service.isForegroundActive() } returns false
            advanceTimeBy(5000); runCurrent()
            coVerify(exactly = 2) { f.service.startAndAwaitForegrounded(any()) }
            coVerify(exactly = 1) { f.provider.prepare(any()) }
            assertNotNull(f.engine.status.value)
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `rejected foreground restoration stops background relay`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.emit(PlaybackTargetEvent.StatusChanged(1, true)); runCurrent()
            every { f.service.isForegroundActive() } returns false
            coEvery { f.service.startAndAwaitForegrounded(any()) } returns
                ForegroundServiceStartResult.StartRejected(IllegalStateException("rejected"))
            advanceTimeBy(5000); runCurrent()
            coVerify { f.coordinator.stop("foreground service failed") }
            assertNull(f.engine.status.value)
        } finally { f.engine.stop(); runCurrent() }
    }
}
