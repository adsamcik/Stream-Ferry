package com.adsamcik.streamferry.playback

import android.content.Context
import com.adsamcik.streamferry.core.adaptive.AdaptiveBitrateController
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
import com.adsamcik.streamferry.data.transcode.StreamingTranscodeSession
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.source.api.TrackRef
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
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineLocalTranscodeTest {
    private enum class SourceCase { FILE, CONTENT_URI, PROVIDER_FILE, PROVIDER_HLS }
    private data class LoadedStream(val url: String, val duration: Long?, val position: Long, val playWhenReady: Boolean)

    @BeforeTest fun mockLocalProbe() {
        mockkObject(LocalMediaProbe)
        every { LocalMediaProbe.probe(any(), any(), any()) } returns compatibleProfile
        every { LocalMediaProbe.isByteSeekable(any(), any()) } returns true
        every { LocalMediaProbe.probeDurationSecondsExact(any(), any()) } returns 3600.0
        mockkConstructor(StreamingTranscodeSession::class)
        // Only first-media readiness is signalled. No source-EOF or export-completion event is sent.
        coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
        every { anyConstructed<StreamingTranscodeSession>().release() } just Runs
    }

    @AfterTest fun releaseLocalProbe() = unmockkAll()

    private class Fixture(scope: TestScope, protocol: Protocol = Protocol.CAST) {
        val events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
        val target = mockk<PlaybackTargetController>(relaxed = true)
        val coordinator = mockk<DefaultPlaybackSessionCoordinator>(relaxed = true)
        val provider = mockk<PlaybackProvider>(relaxed = true)
        val providerSession = mockk<ProviderPlaybackSession>(relaxed = true)
        val requests = mutableListOf<PlaybackRequest>()
        val loads = mutableListOf<LoadedStream>()
        var stops = 0
        val movie = MediaItem("movie", "Movie", null, 3600, null, 123, false)
        var providerStream = StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 100_000)
        var providerAudioTracks = emptyList<com.adsamcik.streamferry.source.api.MediaTrack>()
        var providerSubtitleTracks = emptyList<com.adsamcik.streamferry.source.api.MediaTrack>()
        var providerSourceBitrate: Long? = null
        private val session = SessionRegistry().create("video/mp4", false, 100_000)
        val engine: PlaybackEngine

        init {
            every { target.events } returns events
            every { target.protocol } returns protocol
            coEvery { target.readCurrentVolume() } returns 0.1f
            coEvery { target.stop() } answers { stops++ }
            coEvery { target.load(any(), any(), any(), any(), any(), any()) } coAnswers {
                loads += LoadedStream(firstArg(), arg(3), arg(4), arg(5))
            }
            every { coordinator.active } returns MutableStateFlow(session)
            coEvery { coordinator.startLocalAndBuildUrl(any(), any(), any()) } returns
                (session to ORIGINAL_URL)
            coEvery { coordinator.startClientTranscodeAndBuildUrl(any(), any()) } returns
                (session to TRANSCODE_URL)
            coEvery { coordinator.prepareTranscodeInput(any(), any(), any(), any()) } returns INPUT_URL
            coEvery { coordinator.startAndBuildUrl(any(), any(), any(), any()) } returns
                (session to ORIGINAL_URL)
            every { providerSession.descriptor } answers {
                PlaybackDescriptor(movie.ref, compatibleProfile, 3600, sourceBitrateBps = providerSourceBitrate,
                    audioTracks = providerAudioTracks, subtitleTracks = providerSubtitleTracks, stream = providerStream)
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

        suspend fun start(allowClientTranscode: Boolean = true, filePath: String = "/download/movie.mp4") = engine.playLocal(
            filePath = filePath, contentType = "video/mp4", title = "Movie",
            runtimeSeconds = 3600, allowClientTranscode = allowClientTranscode,
            resumePositionSeconds = 123, target = target, selectedTarget = selected,
        )

        suspend fun startOnline() = engine.play(movie, target, selected, StreamPreferences(maxVideoHeight = 720, maxBitrateBps = 2_000_000))

        suspend fun startSource(source: SourceCase) {
            when (source) {
                SourceCase.FILE -> start(allowClientTranscode = false)
                SourceCase.CONTENT_URI -> start(allowClientTranscode = false, filePath = "content://media/external/video/42")
                SourceCase.PROVIDER_FILE -> startOnline()
                SourceCase.PROVIDER_HLS -> {
                    providerStream = StreamDescriptor(
                        "application/vnd.apple.mpegurl", "hls", isTranscoding = true, isByteSeekable = false,
                        hlsSegmentFormat = com.adsamcik.streamferry.source.api.HlsSegmentFormat.FMP4,
                        totalLength = null,
                    )
                    startOnline()
                }
            }
        }

        suspend fun continueSource(
            source: SourceCase,
            protocol: Protocol,
            position: Long,
            continuation: PlaybackRecoveryContinuation,
            volumeSupported: Boolean = false,
        ) {
            every { target.protocol } returns protocol
            val endpoint = selected.copy(protocol = protocol, capabilities = selected.capabilities.copy(protocol = protocol),
                discoveryMetadata = selected.discoveryMetadata.copy(volumeControlAvailable = volumeSupported))
            when (source) {
                SourceCase.FILE, SourceCase.CONTENT_URI -> engine.playLocal(
                    filePath = if (source == SourceCase.FILE) "/download/movie.mp4" else "content://media/external/video/42",
                    contentType = "video/mp4", title = "Movie", runtimeSeconds = 3600,
                    allowClientTranscode = false, resumePositionSeconds = position,
                    target = target, selectedTarget = endpoint, recoveryContinuation = continuation,
                )
                SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS -> engine.play(
                    movie, target, endpoint, StreamPreferences(maxVideoHeight = 720, maxBitrateBps = 2_000_000),
                    resumePositionOverrideSeconds = position, recoveryContinuation = continuation,
                )
            }
        }
    }

    @Test fun `manual protocol switches retain phone output pause and position for every source in both directions`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.selectPreferredCodec("hevc"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.engine.seekTo(450); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val before = assertNotNull(f.engine.status.value)
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch(), "$source/$protocol")
                val alternate = Protocol.entries.first { it != protocol }
                f.continueSource(source, alternate, before.positionSeconds, continuation); runCurrent()
                val switched = assertNotNull(f.engine.status.value)
                assertEquals(alternate.name, switched.protocolName)
                assertEquals(450L, switched.positionSeconds, "$source/$protocol position")
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol autoplayed")
                assertEquals(LocalTranscodeMode.ON_DEVICE, switched.localTranscodeMode)
                assertEquals("hevc", switched.preferredVideoCodec)
                assertEquals(480, switched.maxVideoHeight)
                assertTrue(switched.isManualMaxVideoHeight)
                assertEquals("HEVC · 480p · on device", switched.outputFormat)
                assertEquals(continuation.session.usage, f.engine.recoverySnapshot().usage)

                // The reverse handoff keeps an explicit original output and resumed playback too.
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                f.engine.resume(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(450, true)); runCurrent()
                val original = assertNotNull(f.engine.status.value)
                val returnContinuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                f.continueSource(source, protocol, original.positionSeconds, returnContinuation); runCurrent()
                assertTrue(f.loads.last().playWhenReady)
                assertEquals(LocalTranscodeMode.ORIGINAL, f.engine.status.value?.localTranscodeMode)
                assertEquals(original.outputFormat, f.engine.status.value?.outputFormat)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `manual protocol connection retries retain paused output and real stop clears choices for every source`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.selectPreferredCodec("h264"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                val alternate = Protocol.entries.first { it != protocol }
                coEvery { f.target.connect(any()) } throws IllegalStateException("Endpoint unavailable")
                assertFailsWith<IllegalStateException> { f.continueSource(source, alternate, 123, continuation) }
                val retry = assertNotNull(f.engine.reserveEndpointConnectionContinuation(alternate))
                coEvery { f.target.connect(any()) } just Runs
                f.continueSource(source, alternate, 123, retry); runCurrent()
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol retry autoplayed")
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                assertEquals("h264", f.engine.status.value?.preferredVideoCodec)
                assertEquals(480, f.engine.status.value?.maxVideoHeight)
                f.engine.stop(); runCurrent()
                // A fresh video's failed connect must not inherit the previous video's pause.
                coEvery { f.target.connect(any()) } throws IllegalStateException("Endpoint unavailable")
                assertFailsWith<IllegalStateException> { f.startSource(source) }
                val freshRetry = assertNotNull(f.engine.reserveEndpointConnectionContinuation(protocol))
                coEvery { f.target.connect(any()) } just Runs
                f.continueSource(source, protocol, 123, freshRetry); runCurrent()
                assertTrue(f.loads.last().playWhenReady)
                assertEquals(LocalTranscodeMode.AUTOMATIC, f.engine.status.value?.localTranscodeMode)
                assertFalse(assertNotNull(f.engine.status.value).isManualMaxVideoHeight)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `manual protocol switches retain paused server codec and resolution choices on both provider paths`() = runTest {
        for (protocol in Protocol.entries) for (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectPreferredCodec("hevc"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, false)); runCurrent()
                val position = assertNotNull(f.engine.status.value).positionSeconds
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                val alternate = Protocol.entries.first { it != protocol }
                f.continueSource(source, alternate, position, continuation); runCurrent()
                val switched = assertNotNull(f.engine.status.value)
                assertEquals(LocalTranscodeMode.AUTOMATIC, switched.localTranscodeMode)
                assertEquals("hevc", switched.preferredVideoCodec)
                assertEquals(480, switched.maxVideoHeight)
                assertTrue(switched.isManualMaxVideoHeight)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(position, switched.positionSeconds)
                assertEquals("hevc", f.requests.last().preferredVideoCodec)
                assertEquals(480, f.requests.last().maxVideoHeight)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `connected and volume reports retain handoff checkpoint through failed preparation and explicit retry for every source`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val volumeReady = CompletableDeferred<Unit>()
            val positions = mutableListOf<Long>()
            var collecting: kotlinx.coroutines.Job? = null
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.selectPreferredCodec("h264"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.engine.seekTo(450); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.engine.status.collect { it?.let { status -> positions += status.recoveryPositionSeconds } }
                }
                coEvery { f.target.connect(any()) } coAnswers {
                    yield() // Register the real event collector before its connection notification.
                    f.events.emit(PlaybackTargetEvent.Connected)
                    yield()
                }
                coEvery { f.target.readCurrentVolume() } coAnswers { volumeReady.await(); 0.1f }
                if (source == SourceCase.FILE || source == SourceCase.CONTENT_URI) {
                    every { LocalMediaProbe.probe(any(), any(), any()) } throws IllegalArgumentException("Probe unavailable")
                } else {
                    coEvery { f.provider.prepare(any()) } returns Result.failure(IllegalStateException("Source unavailable"))
                }
                val alternate = Protocol.entries.first { it != protocol }
                val preparing = async { runCatching { f.continueSource(source, alternate, 450, continuation, volumeSupported = true) } }
                runCurrent()
                assertFalse(preparing.isCompleted)
                assertEquals(450L, f.engine.status.value?.positionSeconds, "$source/$protocol connected checkpoint")
                assertFalse(assertNotNull(f.engine.status.value).playWhenReady)
                volumeReady.complete(Unit); runCurrent()
                assertTrue(preparing.await().isFailure)
                assertTrue(positions.isNotEmpty())
                assertTrue(positions.all { it == 450L }, "$source/$protocol saved a startup position: $positions")
                val retry = assertNotNull(f.engine.continuationForExplicitRetry())
                assertEquals(450L, retry.positionSeconds)
                assertEquals(RecoveryBudgetUsage(), retry.session.usage)
                assertTrue(retry.session.attempts.isEmpty())
                f.engine.stop(); runCurrent()
                assertNull(f.engine.continuationForExplicitRetry(), "real Stop must clear engine retry ownership")
                every { LocalMediaProbe.probe(any(), any(), any()) } returns compatibleProfile
                coEvery { f.provider.prepare(any()) } coAnswers { f.requests += firstArg<PlaybackRequest>(); Result.success(f.providerSession) }
                f.continueSource(source, alternate, 450, retry, volumeSupported = true); runCurrent()
                val restored = assertNotNull(f.engine.status.value)
                assertEquals(450L, restored.positionSeconds)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(LocalTranscodeMode.ON_DEVICE, restored.localTranscodeMode)
                assertEquals("h264", restored.preferredVideoCodec)
                assertEquals(480, restored.maxVideoHeight)
            } finally {
                volumeReady.complete(Unit); collecting?.cancel()
                every { LocalMediaProbe.probe(any(), any(), any()) } returns compatibleProfile
                f.engine.stop(); runCurrent()
            }
        }
    }

    @Test fun `provider track references survive switches and failed switch retry in server and phone output`() = runTest {
        for (protocol in Protocol.entries) for (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
            for (mode in listOf(LocalTranscodeMode.AUTOMATIC, LocalTranscodeMode.ON_DEVICE)) {
                val f = Fixture(this, protocol)
                f.providerAudioTracks = listOf(
                    com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("3"), "eng", "English", isDefault = true),
                    com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("7"), "ces", "Czech"),
                )
                f.providerSubtitleTracks = listOf(com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("12"), "ces", "Czech subtitles"))
                try {
                    f.startSource(source); runCurrent()
                    f.engine.selectAudioTrack(1); runCurrent()
                    f.engine.selectSubtitleTrack(0); runCurrent()
                    if (mode == LocalTranscodeMode.ON_DEVICE) { f.engine.selectLocalTranscodeMode(mode); runCurrent() }
                    f.events.emit(PlaybackTargetEvent.StatusChanged(if (mode == LocalTranscodeMode.ON_DEVICE) 0 else 123, true)); runCurrent()
                    f.engine.pause(); runCurrent()
                    f.events.emit(PlaybackTargetEvent.StatusChanged(if (mode == LocalTranscodeMode.ON_DEVICE) 0 else 123, false)); runCurrent()
                    val position = assertNotNull(f.engine.status.value).positionSeconds
                    val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                    val alternate = Protocol.entries.first { it != protocol }
                    f.continueSource(source, alternate, position, continuation); runCurrent()
                    assertEquals(TrackRef("7"), f.requests.last().audioTrack, "$source/$protocol/$mode audio")
                    assertEquals(TrackRef("12"), f.requests.last().subtitleTrack)
                    assertFalse(f.requests.last().subtitlesDisabled)
                    assertFalse(f.loads.last().playWhenReady)
                    f.events.emit(PlaybackTargetEvent.StatusChanged(if (mode == LocalTranscodeMode.ON_DEVICE) 0 else position, false)); runCurrent()
                    val returning = assertNotNull(f.engine.beginManualProtocolSwitch())
                    coEvery { f.target.connect(any()) } throws IllegalStateException("Endpoint unavailable")
                    assertFailsWith<IllegalStateException> { f.continueSource(source, protocol, position, returning) }
                    val retry = assertNotNull(f.engine.continuationForExplicitRetry())
                    f.engine.stop(); runCurrent()
                    coEvery { f.target.connect(any()) } just Runs
                    f.continueSource(source, protocol, position, retry); runCurrent()
                    val restored = assertNotNull(f.engine.status.value)
                    assertEquals(TrackRef("7"), f.requests.last().audioTrack)
                    assertEquals(TrackRef("12"), f.requests.last().subtitleTrack)
                    assertEquals(1, restored.currentAudioIndex)
                    assertEquals(0, restored.currentSubtitleIndex)
                    assertEquals(listOf("English", "Czech"), restored.audioTracks.map { it.label })
                    assertFalse(f.loads.last().playWhenReady)
                    assertEquals(mode, restored.localTranscodeMode)
                } finally { f.engine.stop(); runCurrent() }
            }
        }
    }

    @Test fun `pinned provider bitrate survives switching and failed connection explicit retry`() = runTest {
        for (protocol in Protocol.entries) for (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
            val f = Fixture(this, protocol)
            f.providerSourceBitrate = 8_000_000
            try {
                f.startSource(source); runCurrent()
                f.engine.selectQuality(1_500_000); runCurrent()
                val cap = assertNotNull(f.engine.status.value).currentBitrateBps
                assertEquals(1_500_000L, cap)
                f.engine.selectPreferredCodec("h264"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, false)); runCurrent()
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                val alternate = Protocol.entries.first { it != protocol }
                f.continueSource(source, alternate, 123, continuation); runCurrent()
                assertEquals(cap, f.requests.last().maxBitrateBps)
                assertTrue(assertNotNull(f.engine.status.value).isManualQuality)
                assertEquals(cap, f.engine.status.value?.currentBitrateBps)
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, false)); runCurrent()
                val returning = assertNotNull(f.engine.beginManualProtocolSwitch())
                coEvery { f.target.connect(any()) } throws IllegalStateException("Endpoint unavailable")
                assertFailsWith<IllegalStateException> { f.continueSource(source, protocol, 123, returning) }
                val retry = assertNotNull(f.engine.continuationForExplicitRetry())
                f.engine.stop(); runCurrent()
                coEvery { f.target.connect(any()) } just Runs
                f.continueSource(source, protocol, 123, retry); runCurrent()
                assertEquals(cap, f.requests.last().maxBitrateBps)
                assertTrue(assertNotNull(f.engine.status.value).isManualQuality)
                assertEquals(cap, f.engine.status.value?.currentBitrateBps)
                assertEquals("h264", f.requests.last().preferredVideoCodec)
                assertEquals(480, f.requests.last().maxVideoHeight)
                assertFalse(f.loads.last().playWhenReady)
                f.engine.selectQuality(null); runCurrent()
                assertFalse(assertNotNull(f.engine.status.value).isManualQuality)
                f.engine.stop(); runCurrent()
                f.startSource(source); runCurrent()
                assertFalse(assertNotNull(f.engine.status.value).isManualQuality)
                assertEquals(2_000_000L, f.requests.last().maxBitrateBps)
            } finally { f.engine.stop(); runCurrent() }
        }
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
            coVerify { f.target.load(TRANSCODE_URL, match { it.mimeType == "application/vnd.apple.mpegurl" }, "Movie", 3477, 0, true) }

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
            coVerify(exactly = 3) { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true) }
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

            f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
            f.engine.selectMaxVideoHeight(480); runCurrent()
            assertEquals(false, f.engine.status.value?.isPlaying)
            assertEquals(200L, f.engine.status.value?.positionSeconds)
            coVerify(exactly = 2) {
                f.target.load(TRANSCODE_URL, any(), "Movie", 3400, 0, false)
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
            coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true) }
            f.engine.pause()
            f.engine.selectMaxVideoHeight(480); runCurrent()
            coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, false) }
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
                coVerifyOrder {
                    anyConstructed<StreamingTranscodeSession>().awaitReady()
                    f.coordinator.startClientTranscodeAndBuildUrl(any(), "127.0.0.1")
                    f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true)
                }
                coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), "127.0.0.1") }
                coVerify { f.target.load(TRANSCODE_URL, match { it.mimeType == (if (protocol == Protocol.CAST) "application/vnd.apple.mpegurl" else "video/mp2t") && !it.isByteSeekable }, "Movie", 3477, 0, true) }
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `online HLS and nonseekable providers use the same phone output controls for Cast and DLNA`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) {
            for (hls in listOf(false, true)) {
                val f = Fixture(this, protocol)
                f.providerStream = StreamDescriptor(
                    contentType = if (hls) "application/vnd.apple.mpegurl" else "video/mp4",
                    outputContainer = if (hls) "hls" else "mp4",
                    hlsSegmentFormat = if (hls) com.adsamcik.streamferry.source.api.HlsSegmentFormat.FMP4 else null,
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
                    coVerify(exactly = 3) { f.coordinator.startClientTranscodeAndBuildUrl(any(), "127.0.0.1") }
                    coVerify(exactly = 3) { f.target.load(TRANSCODE_URL, match { it.mimeType == (if (protocol == Protocol.CAST) "application/vnd.apple.mpegurl" else "video/mp2t") && !it.isByteSeekable }, "Movie", 3477, 0, true) }
                } finally { f.engine.stop(); runCurrent() }
            }
        }
    }

    @Test fun `online phone output preserves pause and position by restarting streaming at seek and restores original provider`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) {
            val f = Fixture(this, protocol)
            try {
                f.startOnline(); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, false)); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                assertEquals(false, f.engine.status.value?.isPlaying)
                assertEquals(200L, f.engine.status.value?.positionSeconds)
                coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3400, 0, false) }
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, false)); runCurrent()
                assertEquals(205L, f.engine.status.value?.positionSeconds)
                val requestsBeforeSeek = f.requests.size
                f.engine.seekTo(300); runCurrent()
                coVerify(exactly = 0) { f.target.seekTo(any()) }
                assertEquals(requestsBeforeSeek + 1, f.requests.size)
                coVerify(exactly = 2) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
                coVerify { f.target.load(TRANSCODE_URL, any(), "Movie", 3300, 0, false) }
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                assertEquals(LocalTranscodeMode.ORIGINAL, f.engine.status.value?.localTranscodeMode)
                assertEquals(300L, f.engine.status.value?.positionSeconds)
                assertEquals(false, f.engine.status.value?.isPlaying)
                coVerify { f.target.load(ORIGINAL_URL, any(), "Movie", 3600, 300, false) }
                assertFalse(f.requests.last().forceTranscode)
                assertEquals(300L, f.requests.last().startPositionSeconds)
                verify(atLeast = 1) { anyConstructed<StreamingTranscodeSession>().release() }
            } finally { f.engine.stop(); runCurrent() }
        }
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
            coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true) }
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
            coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true) }
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
            coVerify(exactly = 1) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 1) { f.target.load(TRANSCODE_URL, any(), "Movie", 3477, 0, true) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `stop cancels a streaming startup before the renderer can load its output`() = runTest {
        val f = Fixture(this, Protocol.DLNA)
        val preparing = CompletableDeferred<Unit>()
        coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers {
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
            coVerify(exactly = 0) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
            coVerify(exactly = 0) { f.target.load(TRANSCODE_URL, any(), any(), any(), any(), any()) }
            verify(atLeast = 1) { anyConstructed<StreamingTranscodeSession>().release() }
            coVerify(atLeast = 1) { f.coordinator.stop(any()) }
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `old renderer updates cannot change a replacement timeline for any source or protocol`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val waiting = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            try {
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, false)); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers {
                    waiting.complete(Unit)
                    ready.await()
                }
                val changing = launch { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE) }
                runCurrent()
                assertTrue(waiting.isCompleted, "$source/$protocol")
                f.events.emit(PlaybackTargetEvent.StatusChanged(201, true))
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true)); runCurrent()
                assertEquals(200L, f.engine.status.value?.positionSeconds, "$source/$protocol accepted old timeline")
                assertEquals(false, f.engine.status.value?.isBuffering)
                ready.complete(Unit); runCurrent(); changing.join()
                assertEquals(0L, f.loads.last().position)
                assertEquals(3400L, f.loads.last().duration)
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, true)); runCurrent()
                assertEquals(205L, f.engine.status.value?.positionSeconds)
                assertEquals(false, f.engine.status.value?.isBuffering)
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, false)); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                assertEquals(false, f.loads.last().playWhenReady, "$source/$protocol lost pause after replacing a buffering stream")
            } finally {
                f.engine.stop(); ready.complete(Unit); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `paused seeks and output changes stay paused and resume at source position for every source and protocol`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.selectPreferredCodec("h264"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.engine.seekTo(300); runCurrent()
                assertEquals(false, f.loads.last().playWhenReady)
                assertEquals(0L, f.loads.last().position)
                assertEquals(3300L, f.loads.last().duration)
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, false)); runCurrent()
                assertEquals(305L, f.engine.status.value?.positionSeconds)
                val loadsBeforeWait = f.loads.size
                advanceTimeBy(StartupWatchdog.GRACE_MS + 1); runCurrent()
                assertEquals(loadsBeforeWait, f.loads.size, "$source/$protocol recovered an intentionally paused load")
                assertNull(f.engine.status.value?.errorMessage)
                assertEquals(false, f.engine.status.value?.isPlaying)
                f.engine.resume(); runCurrent()
                if (protocol == Protocol.DLNA) {
                    assertEquals(loadsBeforeWait + 1, f.loads.size)
                    assertEquals(0L, f.loads.last().position)
                    assertEquals(3295L, f.loads.last().duration)
                    assertEquals(true, f.loads.last().playWhenReady)
                } else assertEquals(loadsBeforeWait, f.loads.size)
                f.events.emit(PlaybackTargetEvent.StatusChanged(if (protocol == Protocol.DLNA) 0 else 5, true)); runCurrent()
                assertEquals(305L, f.engine.status.value?.positionSeconds)
                assertEquals(true, f.engine.status.value?.isPlaying)
                assertEquals("H.264 · 480p · on device", f.engine.status.value?.outputFormat)
                f.engine.selectPreferredCodec("hevc"); runCurrent()
                f.engine.selectMaxVideoHeight(720); runCurrent()
                f.engine.seekTo(600); runCurrent()
                assertEquals(true, f.loads.last().playWhenReady)
                assertEquals(0L, f.loads.last().position)
                assertEquals(3000L, f.loads.last().duration)
                f.events.emit(PlaybackTargetEvent.StatusChanged(2, true)); runCurrent()
                assertEquals(602L, f.engine.status.value?.positionSeconds)
                assertEquals("HEVC · 720p · on device", f.engine.status.value?.outputFormat)
                coVerify(exactly = 0) { f.target.seekTo(any()) }
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `successful codec fallback releases reload guard for remote pause output changes and completion`() = runTest {
        for (protocol in listOf(Protocol.CAST, Protocol.DLNA)) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                val firstOutput = f.engine.status.value?.outputFormat
                val firstLoads = f.loads.size
                f.events.emit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "Unsupported output")); runCurrent()
                assertEquals(firstLoads + 1, f.loads.size, "$source/$protocol did not attempt codec fallback")
                assertNotEquals(firstOutput, f.engine.status.value?.outputFormat)
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, false)); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                assertEquals(false, f.loads.last().playWhenReady, "$source/$protocol lost remote pause after fallback")
                f.events.emit(PlaybackTargetEvent.Ended); runCurrent()
                assertNull(f.engine.status.value, "$source/$protocol ignored normal end after fallback")
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `buffering toggle pauses and resumes intended playback for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true))
                f.events.emit(PlaybackTargetEvent.StatusChanged(5, false)); runCurrent()
                assertEquals(true, f.engine.status.value?.playWhenReady)
                f.engine.togglePlayPause(); runCurrent()
                coVerify(exactly = 1) { f.target.pause() }
                coVerify(exactly = 0) { f.target.play() }
                assertEquals(false, f.engine.status.value?.playWhenReady)
                f.engine.selectMaxVideoHeight(480); runCurrent()
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol lost explicit pause")
                f.engine.togglePlayPause(); runCurrent()
                assertEquals(true, f.engine.status.value?.playWhenReady)
                if (protocol == Protocol.CAST) coVerify(exactly = 1) { f.target.play() }
                else assertTrue(f.loads.last().playWhenReady)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `endpoint reconnect preserves paused and playing phone output for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) for (playing in listOf(false, true)) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(77, true)); runCurrent()
                if (!playing) { f.engine.pause(); runCurrent() }
                f.events.emit(PlaybackTargetEvent.Disconnected); runCurrent()
                assertTrue(f.engine.retrySameEndpointAfterDisconnect())
                runCurrent()
                assertEquals(playing, f.loads.last().playWhenReady, "$source/$protocol reconnect")
                assertEquals(0L, f.loads.last().position)
                assertEquals(200L, f.engine.status.value?.positionSeconds)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `provider automatic output changes preserve pause and buffering intent on both protocols`() = runTest {
        for (protocol in Protocol.entries) for (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol resolution")
                assertEquals(480, f.requests.last().maxVideoHeight)
                f.engine.selectPreferredCodec("hevc"); runCurrent()
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol codec")
                assertEquals("hevc", f.requests.last().preferredVideoCodec)
                f.engine.resume(); runCurrent()
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true))
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                f.engine.selectMaxVideoHeight(720); runCurrent()
                assertTrue(f.loads.last().playWhenReady, "$source/$protocol buffering resolution")
                f.engine.seekTo(300); runCurrent()
                assertTrue(f.engine.status.value?.playWhenReady == true)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `explicit output choices leave Original and encode for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                assertEquals(480, f.engine.status.value?.maxVideoHeight)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(TRANSCODE_URL, f.loads.last().url)
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                f.engine.selectPreferredCodec("h264"); runCurrent()
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                assertEquals("h264", f.engine.status.value?.preferredVideoCodec)
                assertEquals(TRANSCODE_URL, f.loads.last().url)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(123L, f.engine.status.value?.positionSeconds)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `repeated buffering polls count one stall and completion clears transport intent`() = runTest {
        mockkConstructor(AdaptiveBitrateController::class)
        every { anyConstructed<AdaptiveBitrateController>().recordRebuffer(any()) } just Runs
        for (protocol in Protocol.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startOnline(); runCurrent()
                f.engine.setAutoAdvance(true)
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                repeat(4) { f.events.emit(PlaybackTargetEvent.BufferingChanged(true)); runCurrent() }
                f.events.emit(PlaybackTargetEvent.BufferingChanged(false)); runCurrent()
                f.events.emit(PlaybackTargetEvent.Ended); runCurrent()
                val completed = assertNotNull(f.engine.status.value)
                assertEquals(PlaybackPhase.COMPLETED, completed.phase)
                assertFalse(completed.playWhenReady)
                assertFalse(completed.isPlaying)
                assertFalse(completed.isBuffering)
            } finally { f.engine.stop(); runCurrent() }
        }
        verify(exactly = Protocol.entries.size) {
            anyConstructed<AdaptiveBitrateController>().recordRebuffer(any())
        }
    }

    @Test fun `end seeks complete once without exporting empty media for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val completions = mutableListOf<PlaybackCompletion>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.engine.endOfMedia.collect { completions += it }
            }
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.setAutoAdvance(true)
                val loads = f.loads.size
                val stops = f.stops
                f.engine.seekTo(3600); runCurrent()
                val ended = assertNotNull(f.engine.status.value)
                assertEquals(PlaybackPhase.COMPLETED, ended.phase, "$source/$protocol")
                assertEquals(3600L, ended.positionSeconds)
                assertFalse(ended.isPlaying)
                assertFalse(ended.playWhenReady)
                assertNull(ended.errorMessage)
                assertEquals(loads, f.loads.size, "EOF must not start another export")
                assertEquals(1, completions.size)
                if (protocol == Protocol.CAST) assertEquals(stops + 1, f.stops)
                f.engine.seekTo(3700); runCurrent()
                f.events.emit(PlaybackTargetEvent.Ended); runCurrent()
                assertEquals(1, completions.size, "duplicate completion for $source/$protocol")
                verify(atLeast = 1) { anyConstructed<StreamingTranscodeSession>().release() }
            } finally { collecting.cancel(); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `paused skip beyond the end completes and tears down every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val completions = mutableListOf<PlaybackCompletion>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.engine.endOfMedia.collect { completions += it }
            }
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.seekTo(3550); runCurrent()
                f.engine.pause(); runCurrent()
                val loads = f.loads.size
                f.engine.skip(100); runCurrent()
                assertEquals(1, completions.size, "$source/$protocol")
                assertNull(f.engine.status.value, "completion without autoplay must stop")
                assertEquals(loads, f.loads.size)
            } finally { collecting.cancel(); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `startup pause and resume return before first media and control final load for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val ready = CompletableDeferred<Unit>()
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                val stops = f.stops
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers {
                    if (protocol == Protocol.CAST) assertTrue(f.stops > stops, "old Cast buffer was not stopped")
                    ready.await()
                }
                val seeking = launch { f.engine.seekTo(300) }; runCurrent()
                val paused = launch { f.engine.pause() }; runCurrent()
                assertTrue(paused.isCompleted, "$source/$protocol pause blocked")
                assertFalse(ready.isCompleted)
                assertEquals(false, f.engine.status.value?.playWhenReady)
                val resumed = launch { f.engine.resume() }; runCurrent()
                assertTrue(resumed.isCompleted, "$source/$protocol resume blocked")
                assertEquals(true, f.engine.status.value?.playWhenReady)
                f.engine.togglePlayPause(); runCurrent()
                assertEquals(false, f.engine.status.value?.playWhenReady)
                ready.complete(Unit); runCurrent(); seeking.join()
                assertFalse(f.loads.last().playWhenReady, "$source/$protocol autoplayed despite pending pause")
                assertEquals(300L, f.engine.status.value?.positionSeconds)
                coVerify(exactly = 0) { f.target.play() }
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `new seek codec and resolution replace blocked startup and retain newest choices for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val ready = CompletableDeferred<Unit>()
            var starts = 0
            var cancelled = 0
            coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers {
                    starts++
                    try { ready.await() } finally { if (!ready.isCompleted) cancelled++ }
                }
                val loads = f.loads.size
                val seeking = launch { f.engine.seekTo(300) }; runCurrent()
                val newerSeek = launch { f.engine.seekTo(600) }; runCurrent()
                assertTrue(seeking.isCompleted, "$source/$protocol old seek still blocks")
                assertEquals(2, starts)
                val format = launch { f.engine.selectPreferredCodec("h264") }; runCurrent()
                assertTrue(newerSeek.isCompleted)
                val resolution = launch { f.engine.selectMaxVideoHeight(480) }; runCurrent()
                assertTrue(format.isCompleted)
                assertEquals(3, cancelled)
                assertEquals(loads, f.loads.size, "superseded startup loaded stale media")
                f.engine.pause(); runCurrent()
                ready.complete(Unit); runCurrent(); resolution.join()
                assertEquals(loads + 1, f.loads.size)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(600L, f.engine.status.value?.positionSeconds)
                assertEquals("H.264 · 480p · on device", f.engine.status.value?.outputFormat)
                assertNull(f.engine.status.value?.errorMessage)
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `pause during receiver load is reconciled and initial startup can be superseded for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val loading = CompletableDeferred<Unit>()
            var first = true
            coEvery { f.target.load(any(), any(), any(), any(), any(), any()) } coAnswers {
                f.loads += LoadedStream(firstArg(), arg(3), arg(4), arg(5))
                if (first) { first = false; loading.await() }
            }
            try {
                val starting = launch { f.startSource(source) }; runCurrent()
                val paused = launch { f.engine.pause() }; runCurrent()
                assertTrue(paused.isCompleted, "$source/$protocol initial pause blocked")
                val seeking = launch { f.engine.seekTo(450) }; runCurrent(); seeking.join()
                assertTrue(starting.isCompleted)
                assertFalse(starting.isCancelled, "supersession must not cancel the playback caller")
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(450L, f.engine.status.value?.positionSeconds)
                assertNull(f.engine.status.value?.errorMessage)
                if (source == SourceCase.PROVIDER_FILE || source == SourceCase.PROVIDER_HLS) {
                    coVerify(exactly = 1) { f.providerSession.mediaSegments() }
                }

                val accepted = CompletableDeferred<Unit>()
                coEvery { f.target.load(any(), any(), any(), any(), any(), any()) } coAnswers {
                    f.loads += LoadedStream(firstArg(), arg(3), arg(4), arg(5))
                    accepted.await()
                }
                val changing = launch { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE) }; runCurrent()
                f.engine.resume(); runCurrent()
                accepted.complete(Unit); runCurrent(); changing.join()
                coVerify(exactly = 1) { f.target.play() }
                assertEquals(true, f.engine.status.value?.playWhenReady)
            } finally { loading.complete(Unit); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `queued output choices and repeated selections retain latest intent for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val ready = CompletableDeferred<Unit>()
            var starts = 0
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers { starts++; ready.await() }
                val first = launch { f.engine.seekTo(300) }; runCurrent()
                val format = launch { f.engine.selectPreferredCodec("h264") }
                val height = launch { f.engine.selectMaxVideoHeight(480) }
                val seek = launch { f.engine.seekTo(700) }
                runCurrent()
                assertTrue(first.isCompleted && format.isCompleted && height.isCompleted)
                assertEquals(2, starts, "queued obsolete requests started encoding for $source/$protocol")
                f.engine.selectPreferredCodec("av1")
                f.engine.selectMaxVideoHeight(1234)
                runCurrent()
                assertFalse(seek.isCompleted, "invalid selections cancelled valid startup")
                assertEquals(2, starts)
                val same = launch { f.engine.selectMaxVideoHeight(480) }; runCurrent()
                assertTrue(seek.isCompleted)
                assertEquals(3, starts, "repeating a pending selection must still finish startup")
                ready.complete(Unit); runCurrent(); same.join()
                assertEquals(700L, f.engine.status.value?.positionSeconds)
                assertEquals("H.264 · 480p · on device", f.engine.status.value?.outputFormat)
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `end seek supersedes pending startup immediately for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val ready = CompletableDeferred<Unit>()
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                f.engine.setAutoAdvance(true)
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers { ready.await() }
                val loads = f.loads.size
                val seeking = launch { f.engine.seekTo(300) }; runCurrent()
                val completing = launch { f.engine.seekTo(3600) }; runCurrent()
                assertTrue(seeking.isCompleted && completing.isCompleted, "$source/$protocol EOF remained blocked")
                assertFalse(ready.isCompleted)
                assertEquals(PlaybackPhase.COMPLETED, f.engine.status.value?.phase)
                assertEquals(3600L, f.engine.status.value?.positionSeconds)
                assertEquals(loads, f.loads.size)
                assertNull(f.engine.status.value?.errorMessage)
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `queued relative skips accumulate while startup is blocked for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val ready = CompletableDeferred<Unit>()
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers { ready.await() }
                val one = launch { f.engine.skip(30) }; runCurrent()
                val two = launch { f.engine.skip(30) }
                val three = launch { f.engine.skip(30) }
                runCurrent()
                assertTrue(one.isCompleted && two.isCompleted)
                assertFalse(three.isCompleted)
                ready.complete(Unit); runCurrent(); three.join()
                assertEquals(213L, f.engine.status.value?.positionSeconds, "$source/$protocol lost a skip")
                assertEquals(3387L, f.loads.last().duration)
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `pause after load acceptance reaches receiver for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                val initialLoads = f.loads.size
                var requested = false
                val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.engine.status.collect { status ->
                        if (!requested && status?.positionSeconds == 300L && f.loads.size > initialLoads) {
                            requested = true
                            launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.pause() }
                        }
                    }
                }
                f.engine.seekTo(300); runCurrent()
                assertTrue(requested)
                coVerify(exactly = 1) { f.target.pause() }
                assertEquals(false, f.engine.status.value?.playWhenReady)
                collector.cancel()
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    private companion object {
        const val ORIGINAL_URL = "http://127.0.0.1/session/test/stream"
        const val TRANSCODE_URL = "http://127.0.0.1/session/test/transcode.m3u8"
        const val INPUT_URL = "http://127.0.0.1/session/input/stream"
        val compatibleProfile = MediaProfile(
            "mp4", "h264", widthPx = 1920, heightPx = 1080, audioCodec = "aac",
        )
    }
}
