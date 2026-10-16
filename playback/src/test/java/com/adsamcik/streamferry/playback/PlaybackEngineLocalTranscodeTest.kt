package com.adsamcik.streamferry.playback

import android.content.Context
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.LocalTranscodeMode
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.data.transcode.PreparedTranscodeFileSession
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartPath
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.PlaybackProvider
import com.adsamcik.streamferry.source.api.PlaybackDescriptor
import com.adsamcik.streamferry.source.api.PlaybackRequest
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import com.adsamcik.streamferry.source.api.StreamDescriptor
import io.mockk.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineLocalTranscodeTest {
    @BeforeTest fun mockLocalProbe() {
        mockkObject(LocalMediaProbe)
        every { LocalMediaProbe.probe(any(), any(), any()) } returns compatibleProfile
        every { LocalMediaProbe.isByteSeekable(any(), any()) } returns true
        every { LocalMediaProbe.probeDurationSecondsExact(any(), any()) } returns 3600.0
        mockkConstructor(PreparedTranscodeFileSession::class)
        every { anyConstructed<PreparedTranscodeFileSession>().file } returns PREPARED_FILE
        coEvery { anyConstructed<PreparedTranscodeFileSession>().prepare(any()) } returns PREPARED_FILE
        every { anyConstructed<PreparedTranscodeFileSession>().release() } just Runs
    }

    @AfterTest fun releaseLocalProbe() = unmockkAll()

    private class Fixture(scope: TestScope, protocol: Protocol = Protocol.CAST) {
        val events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
        val target = mockk<PlaybackTargetController>(relaxed = true)
        val coordinator = mockk<DefaultPlaybackSessionCoordinator>(relaxed = true)
        val provider = mockk<PlaybackProvider>(relaxed = true)
        val providerSession = mockk<ProviderPlaybackSession>(relaxed = true)
        val requests = mutableListOf<PlaybackRequest>()
        val movie = MediaItem("movie", "Movie", null, 3600, null, 123, false)
        var providerStream = StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 100_000)
        private val session = SessionRegistry().create("video/mp4", false, 100_000)
        val engine: PlaybackEngine

        init {
            every { target.events } returns events
            every { target.protocol } returns protocol
            coEvery { target.readCurrentVolume() } returns 0.1f
            every { coordinator.active } returns MutableStateFlow(session)
            coEvery { coordinator.startLocalAndBuildUrl(any(), any(), any()) } returns
                (session to ORIGINAL_URL)
            coEvery { coordinator.startClientTranscodeAndBuildUrl(any(), any()) } returns
                (session to TRANSCODE_URL)
            coEvery { coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) } returns
                (session to PREPARED_URL)
            coEvery { coordinator.prepareTranscodeInput(any(), any(), any(), any()) } returns INPUT_URL
            coEvery { coordinator.startAndBuildUrl(any(), any(), any(), any()) } returns
                (session to ORIGINAL_URL)
            every { providerSession.descriptor } answers {
                PlaybackDescriptor(movie.ref, compatibleProfile, 3600, stream = providerStream)
            }
            coEvery { providerSession.mediaSegments() } returns emptyList()
            coEvery { provider.prepare(any()) } answers {
                requests += firstArg<PlaybackRequest>()
                Result.success(providerSession)
            }
            val service = mockk<PlaybackServiceController>(relaxed = true)
            every { service.isForegroundActive() } returns true
            coEvery { service.startAndAwaitForegrounded(any()) } returns
                ForegroundServiceStartResult.Foregrounded(ProxyForegroundStartPath.START_SERVICE)
            val network = mockk<NetworkInfoProvider>()
            every { network.lanIpv4() } returns "127.0.0.1"
            val context = mockk<Context>(relaxed = true)
            every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir"), "local-transcode-test")
            engine = PlaybackEngine(
                provider, coordinator, mockk<LocalProxyServer>(relaxed = true), network, service,
                mockk<DiagnosticSink>(relaxed = true), context, mockk(relaxed = true),
                { DeviceEncodeCapabilities(ResolutionTier.FHD_1080P, ResolutionTier.HD_720P) },
                mockk(relaxed = true),
                clock = { scope.testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(scope.testScheduler),
                elapsedRealtime = { scope.testScheduler.currentTime },
            )
        }

        private val selected = DiscoveredTarget(
            "tv", "TV", protocol,
            TargetCapabilities(
                protocol, supportedContainers = setOf("mp4"),
                supportedVideoCodecs = setOf("h264", "hevc"),
                supportedAudioCodecs = setOf("aac"), supportsHevc = true,
            ), null,
        )

        suspend fun start(allowClientTranscode: Boolean = true) = engine.playLocal(
            filePath = "/download/movie.mp4", contentType = "video/mp4", title = "Movie",
            runtimeSeconds = 3600, allowClientTranscode = allowClientTranscode,
            resumePositionSeconds = 123, target = target, selectedTarget = selected,
        )

        suspend fun startOnline() = engine.play(movie, target, selected, StreamPreferences(maxVideoHeight = 720, maxBitrateBps = 2_000_000))
    }

    @Test fun `compatible local original offers output controls and explicit transcode overrides automatic preference`() = runTest {
        val f = Fixture(this)
        try {
            f.start(allowClientTranscode = false); runCurrent()
            val original = assertNotNull(f.engine.status.value)
            assertEquals("Direct play", original.streamMode)
            assertEquals(LocalTranscodeMode.AUTOMATIC, original.localTranscodeMode)
            assertEquals(listOf("hevc", "h264"), original.availableVideoCodecs)
            assertNotNull(original.automaticMaxVideoHeight)
            assertNull(original.localTranscodeUnavailableReason)

            f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
            val transcoded = assertNotNull(f.engine.status.value)
            assertEquals("On-device transcode", transcoded.streamMode)
            assertEquals(LocalTranscodeMode.ON_DEVICE, transcoded.localTranscodeMode)
            assertEquals(123L, transcoded.positionSeconds)
            coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), "127.0.0.1") }
            coVerify { f.target.load(TRANSCODE_URL, match { it.mimeType == "application/vnd.apple.mpegurl" }, "Movie", 3600, 123, true) }

            f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
            assertEquals("Direct play", f.engine.status.value?.streamMode)
            assertEquals(LocalTranscodeMode.ORIGINAL, f.engine.status.value?.localTranscodeMode)
            assertNull(f.engine.status.value?.outputFormat)
            coVerify(exactly = 2) { f.coordinator.startLocalAndBuildUrl(any(), any(), any()) }
            coVerify(exactly = 2) { f.target.load(ORIGINAL_URL, any(), "Movie", 3600, 123, true) }
            coVerify(exactly = 1) { f.target.connect(any()) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `codec and resolution picks start local transcoding and honor each codec hardware ceiling`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.engine.selectPreferredCodec("hevc"); runCurrent()
            assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
            assertEquals("hevc", f.engine.status.value?.preferredVideoCodec)
            assertEquals("HEVC · 720p · on device", f.engine.status.value?.outputFormat)

            f.engine.selectMaxVideoHeight(480); runCurrent()
            assertEquals(480, f.engine.status.value?.maxVideoHeight)
            assertEquals(true, f.engine.status.value?.isManualMaxVideoHeight)
            assertEquals("HEVC · 480p · on device", f.engine.status.value?.outputFormat)

            f.engine.selectPreferredCodec("h264"); runCurrent()
            assertEquals("H.264 · 480p · on device", f.engine.status.value?.outputFormat)
            assertEquals(123L, f.engine.status.value?.positionSeconds)
            coVerify(exactly = 3) { f.target.load(TRANSCODE_URL, any(), "Movie", 3600, 123, true) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `resolution pick on direct local playback starts phone output and unsupported codec leaves it unchanged`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.engine.selectMaxVideoHeight(720); runCurrent()
            assertEquals("On-device transcode", f.engine.status.value?.streamMode)
            assertEquals(720, f.engine.status.value?.maxVideoHeight)
            val output = f.engine.status.value?.outputFormat
            f.engine.selectPreferredCodec("av1"); runCurrent()
            assertEquals(output, f.engine.status.value?.outputFormat)
            coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `local output changes preserve a paused renderer and its current position`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            assertTrue(f.events.tryEmit(PlaybackTargetEvent.StatusChanged(123, true)))
            runCurrent()
            assertTrue(f.events.tryEmit(PlaybackTargetEvent.StatusChanged(200, true)))
            runCurrent()
            assertTrue(f.events.tryEmit(PlaybackTargetEvent.StatusChanged(200, false)))
            runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)

            f.engine.selectPreferredCodec("hevc"); runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)
            assertEquals(200L, f.engine.status.value?.positionSeconds)

            f.engine.selectMaxVideoHeight(480); runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)
            assertEquals(200L, f.engine.status.value?.positionSeconds)
            coVerify(exactly = 2) {
                f.target.load(TRANSCODE_URL, any(), "Movie", 3600, 200, false)
            }

            f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)
            assertEquals(200L, f.engine.status.value?.positionSeconds)
            coVerify(exactly = 1) {
                f.target.load(ORIGINAL_URL, any(), "Movie", 3600, 200, false)
            }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `buffering retains play intent while an explicit pause during buffering stays paused`() = runTest {
        val f = Fixture(this)
        try {
            f.start(); runCurrent()
            f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
            f.events.emit(PlaybackTargetEvent.BufferingChanged(true)); runCurrent()
            f.events.emit(PlaybackTargetEvent.StatusChanged(123, false)); runCurrent()
            f.engine.selectPreferredCodec("h264"); runCurrent()
            coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3600, 123, true) }
            f.engine.pause()
            f.engine.selectMaxVideoHeight(480); runCurrent()
            coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3600, 123, false) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `HDR unknown metadata DLNA and nonseekable local input all offer phone transcoding`() = runTest {
        val cases = listOf(
            compatibleProfile.copy(isHdr = true, bitDepth = 10) to Protocol.CAST,
            null to Protocol.CAST,
            compatibleProfile to Protocol.DLNA,
            compatibleProfile to Protocol.CAST,
        )
        for ((index, case) in cases.withIndex()) {
            val (profile, protocol) = case
            every { LocalMediaProbe.probe(any(), any(), any()) } returns profile
            every { LocalMediaProbe.isByteSeekable(any(), any()) } returns (index != 3)
            every { LocalMediaProbe.probeDurationSecondsExact(any(), any()) } returns if (index == 3) null else 3600.0
            val f = Fixture(this, protocol)
            try {
                f.start(allowClientTranscode = false); runCurrent()
                val status = assertNotNull(f.engine.status.value)
                assertEquals("Direct play", status.streamMode)
                assertNull(status.localTranscodeUnavailableReason)
                assertTrue(status.availableVideoCodecs.isNotEmpty())
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                assertEquals("On-device transcode", f.engine.status.value?.streamMode)
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                coVerify(exactly = 0) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
                coVerify(exactly = 1) { f.coordinator.startPreparedTranscodeAndBuildUrl(PREPARED_FILE.absolutePath, "127.0.0.1") }
                coVerify { f.target.load(PREPARED_URL, match { it.mimeType == "video/mp4" && it.isByteSeekable }, "Movie", 3600, 123, true) }
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `online HLS and nonseekable providers use the same phone output controls for Cast and DLNA`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) {
            val f = Fixture(this, protocol)
            f.providerStream = StreamDescriptor(
                contentType = if (protocol == Protocol.CAST) "application/vnd.apple.mpegurl" else "video/mp4",
                outputContainer = if (protocol == Protocol.CAST) "hls" else "mp4",
                hlsSegmentFormat = if (protocol == Protocol.CAST) com.adsamcik.streamferry.source.api.HlsSegmentFormat.FMP4 else null,
                isTranscoding = true, isByteSeekable = false, totalLength = null,
            )
            try {
                f.startOnline(); runCurrent()
                assertNull(f.engine.status.value?.localTranscodeUnavailableReason)
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.selectPreferredCodec("h264"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                val status = assertNotNull(f.engine.status.value)
                assertEquals(LocalTranscodeMode.ON_DEVICE, status.localTranscodeMode)
                assertEquals("H.264 · 480p · on device", status.outputFormat)
                assertEquals(123L, status.positionSeconds)
                val request = f.requests.last()
                assertFalse(request.forceTranscode)
                assertEquals(0L, request.startPositionSeconds)
                assertNull(request.maxBitrateBps)
                assertNull(request.preferredVideoCodec)
                assertEquals(Int.MAX_VALUE, request.maxVideoHeight)
                coVerify(exactly = 3) { f.coordinator.prepareTranscodeInput(f.providerSession, "127.0.0.1", 123, any()) }
                coVerify(exactly = 3) { f.coordinator.startPreparedTranscodeAndBuildUrl(PREPARED_FILE.absolutePath, "127.0.0.1") }
                coVerify(exactly = 3) { f.target.load(PREPARED_URL, match { it.mimeType == "video/mp4" && it.isByteSeekable }, "Movie", 3600, 123, true) }
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `online phone output preserves pause and position seeks without encoding again and restores original provider`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        try {
            f.startOnline(); runCurrent()
            f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
            f.events.emit(PlaybackTargetEvent.StatusChanged(200, true)); runCurrent()
            f.events.emit(PlaybackTargetEvent.StatusChanged(200, false)); runCurrent()
            f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)
            assertEquals(200L, f.engine.status.value?.positionSeconds)
            coVerify { f.target.load(PREPARED_URL, any(), "Movie", 3600, 200, false) }
            val requestsBeforeSeek = f.requests.size
            f.engine.seekTo(300); runCurrent()
            coVerify(exactly = 1) { f.target.seekTo(300) }
            assertEquals(requestsBeforeSeek, f.requests.size)
            coVerify(exactly = 1) { f.coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) }
            f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
            assertEquals(LocalTranscodeMode.ORIGINAL, f.engine.status.value?.localTranscodeMode)
            assertEquals(300L, f.engine.status.value?.positionSeconds)
            assertEquals(false, f.engine.status.value?.isPlaying)
            coVerify { f.target.load(ORIGINAL_URL, any(), "Movie", 3600, 300, false) }
            assertFalse(f.requests.last().forceTranscode)
            assertEquals(300L, f.requests.last().startPositionSeconds)
            verify(atLeast = 1) { anyConstructed<PreparedTranscodeFileSession>().release() }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `automatic startup uses phone output when initial unforced provider negotiation fails`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        coEvery { f.provider.prepare(any()) } answers {
            f.requests += firstArg<PlaybackRequest>()
            if (f.requests.size == 1) Result.failure(IllegalStateException("Source negotiation unavailable"))
            else Result.success(f.providerSession)
        }
        try {
            f.startOnline(); runCurrent()
            assertEquals(LocalTranscodeMode.AUTOMATIC, f.engine.status.value?.localTranscodeMode)
            assertEquals("On-device transcode", f.engine.status.value?.streamMode)
            assertEquals(123L, f.engine.status.value?.positionSeconds)
            assertEquals(listOf(false, false), f.requests.map { it.forceTranscode })
            assertEquals(0L, f.requests.last().startPositionSeconds)
            assertEquals(Int.MAX_VALUE, f.requests.last().maxVideoHeight)
            coVerify(exactly = 0) { f.target.load(ORIGINAL_URL, any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { f.coordinator.prepareTranscodeInput(f.providerSession, "127.0.0.1", 123, any()) }
            coVerify(exactly = 1) { f.coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(PREPARED_URL, any(), "Movie", 3600, 123, true) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `automatic startup uses phone output when renderer rejects original and server preparation fails`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        coEvery { f.provider.prepare(any()) } answers {
            val request = firstArg<PlaybackRequest>()
            f.requests += request
            if (request.forceTranscode) Result.failure(IllegalStateException("Server conversion unavailable"))
            else Result.success(f.providerSession)
        }
        coEvery { f.target.load(ORIGINAL_URL, any(), any(), any(), any(), any()) } throws IllegalStateException("Unsupported original")
        try {
            f.startOnline(); runCurrent()
            val status = assertNotNull(f.engine.status.value)
            assertEquals(LocalTranscodeMode.AUTOMATIC, status.localTranscodeMode)
            assertEquals("On-device transcode", status.streamMode)
            assertEquals(123L, status.positionSeconds)
            assertEquals(listOf(false, true, false), f.requests.map { it.forceTranscode })
            assertEquals(0L, f.requests.last().startPositionSeconds)
            assertEquals(Int.MAX_VALUE, f.requests.last().maxVideoHeight)
            coVerify(exactly = 1) { f.target.load(ORIGINAL_URL, any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { f.coordinator.prepareTranscodeInput(f.providerSession, "127.0.0.1", 123, any()) }
            coVerify(exactly = 1) { f.coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(PREPARED_URL, any(), "Movie", 3600, 123, true) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `automatic startup uses phone output after renderer rejects original and prepared server stream`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        coEvery { f.provider.prepare(any()) } answers {
            val request = firstArg<PlaybackRequest>()
            f.requests += request
            f.providerStream = StreamDescriptor(
                "video/mp4", "mp4", isTranscoding = request.forceTranscode,
                isByteSeekable = !request.forceTranscode, totalLength = null,
            )
            Result.success(f.providerSession)
        }
        coEvery { f.target.load(ORIGINAL_URL, any(), any(), any(), any(), any()) } throws IllegalStateException("Renderer rejected stream")
        try {
            f.startOnline(); runCurrent()
            val status = assertNotNull(f.engine.status.value)
            assertEquals(LocalTranscodeMode.AUTOMATIC, status.localTranscodeMode)
            assertEquals("On-device transcode", status.streamMode)
            assertEquals(123L, status.positionSeconds)
            assertEquals(listOf(false, true, false), f.requests.map { it.forceTranscode })
            coVerify(exactly = 2) { f.target.load(ORIGINAL_URL, any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { f.coordinator.prepareTranscodeInput(f.providerSession, "127.0.0.1", 123, any()) }
            coVerify(exactly = 1) { f.coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(PREPARED_URL, any(), "Movie", 3600, 123, true) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `stop cancels an online phone preparation before the renderer can load its output`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        val preparing = CompletableDeferred<Unit>()
        coEvery { anyConstructed<PreparedTranscodeFileSession>().prepare(any()) } coAnswers {
            preparing.complete(Unit)
            awaitCancellation()
        }
        try {
            f.startOnline(); runCurrent()
            val conversion = launch { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE) }
            runCurrent()
            assertTrue(preparing.isCompleted)
            f.engine.stop(); runCurrent()
            assertTrue(conversion.isCancelled)
            assertNull(f.engine.status.value)
            coVerify(exactly = 0) { f.coordinator.startPreparedTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 0) { f.target.load(PREPARED_URL, any(), any(), any(), any(), any()) }
            verify(atLeast = 1) { anyConstructed<PreparedTranscodeFileSession>().release() }
            coVerify(atLeast = 1) { f.coordinator.stop(any()) }
        } finally { f.engine.stop(); runCurrent() }
    }

    private companion object {
        const val ORIGINAL_URL = "http://127.0.0.1/session/test/stream"
        const val TRANSCODE_URL = "http://127.0.0.1/session/test/transcode.m3u8"
        const val PREPARED_URL = "http://127.0.0.1/session/test/prepared.mp4"
        const val INPUT_URL = "http://127.0.0.1/session/input/stream"
        val PREPARED_FILE = File(System.getProperty("java.io.tmpdir"), "prepared-transcode-test.mp4")
        val compatibleProfile = MediaProfile(
            "mp4", "h264", widthPx = 1920, heightPx = 1080, audioCodec = "aac",
        )
    }
}
