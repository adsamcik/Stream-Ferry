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
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.data.transcode.StreamingTranscodeSession
import com.adsamcik.streamferry.data.subtitle.LocalSubtitleReader
import com.adsamcik.streamferry.data.subtitle.LocalSubtitleTrack
import com.adsamcik.streamferry.data.subtitle.LocalSubtitleSource
import com.adsamcik.streamferry.data.audio.LocalAudioTrack
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        mockkObject(LocalSubtitleReader)
        coEvery { LocalSubtitleReader.discover(any(), any(), any()) } returns emptyList()
        coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } returns emptyList()
        // Only first-media readiness is signalled. No source-EOF or export-completion event is sent.
        coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
        every { anyConstructed<StreamingTranscodeSession>().release() } just Runs
    }

    @AfterTest fun releaseLocalProbe() = unmockkAll()

    private class Fixture(scope: TestScope, protocol: Protocol = Protocol.CAST,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)) {
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
        var providerMediaVersion: String? = null
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
                    audioTracks = providerAudioTracks, subtitleTracks = providerSubtitleTracks, stream = providerStream,
                    mediaVersion = providerMediaVersion)
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
                dispatcher = dispatcher,
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

        suspend fun start(allowClientTranscode: Boolean = true, filePath: String = "/download/movie.mp4", prefs: StreamPreferences = StreamPreferences()) = engine.playLocal(
            filePath = filePath, contentType = "video/mp4", title = "Movie",
            runtimeSeconds = 3600, allowClientTranscode = allowClientTranscode,
            resumePositionSeconds = 123, target = target, selectedTarget = selected,
            prefs = prefs,
        )

        suspend fun startOnline(prefs: StreamPreferences = StreamPreferences(maxVideoHeight = 720, maxBitrateBps = 2_000_000)) = engine.play(movie, target, selected, prefs)

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

    @Test fun `audio language and captions survive seeks output changes handoffs and failed retries for every source`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } returns audioTracks
            val f = Fixture(this, protocol)
            f.providerMediaVersion = "version"
            f.providerAudioTracks = audioTracks.map { com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("version:${it.extractorTrackId}"), it.language, it.label, it.isDefault) }
            every { LocalSubtitleReader.external(any(), any()) } returns LocalSubtitleTrack(
                0, "ces", "Czech captions", source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"),
            )
            every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns mockk(relaxed = true)
            try {
                f.startSource(source); runCurrent()
                assertEquals(0, f.engine.status.value?.currentAudioIndex)
                f.engine.selectAudioTrack(1); runCurrent()
                f.engine.attachSubtitle("content://subtitles/42"); runCurrent()
                val captions = assertNotNull(f.engine.status.value?.currentSubtitleIndex)
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.engine.seekTo(450); runCurrent()
                f.engine.selectPreferredCodec("hevc"); runCurrent()
                f.engine.selectMaxVideoHeight(480); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                assertEquals(1, f.engine.status.value?.currentAudioIndex)
                assertEquals(captions, f.engine.status.value?.currentSubtitleIndex)
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                val alternate = Protocol.entries.first { it != protocol }
                f.continueSource(source, alternate, 450, continuation); runCurrent()
                assertEquals(1, f.engine.status.value?.currentAudioIndex)
                assertEquals(captions, f.engine.status.value?.currentSubtitleIndex)
                assertEquals(450L, f.engine.status.value?.positionSeconds)
                assertEquals(480, f.engine.status.value?.maxVideoHeight)
                assertFalse(f.loads.last().playWhenReady)
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val returning = assertNotNull(f.engine.beginManualProtocolSwitch())
                coEvery { f.target.connect(any()) } throws IllegalStateException("TV unavailable")
                assertFailsWith<IllegalStateException> { f.continueSource(source, protocol, 450, returning) }
                val retry = assertNotNull(f.engine.continuationForExplicitRetry())
                f.engine.stop(); runCurrent()
                coEvery { f.target.connect(any()) } just Runs
                f.continueSource(source, protocol, 450, retry); runCurrent()
                assertEquals(1, f.engine.status.value?.currentAudioIndex)
                assertEquals(captions, f.engine.status.value?.currentSubtitleIndex)
                assertFalse(f.loads.last().playWhenReady)
                if (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
                    assertEquals(TrackRef("version:9"), f.requests.last().audioTrack)
                    assertTrue(f.requests.last().forceTranscode)
                    assertEquals("version", f.requests.last().mediaVersion)
                }
                f.engine.selectAudioTrack(2); runCurrent()
                assertEquals("English commentary", f.engine.status.value?.audioTracks?.single { it.index == 2 }?.label)
                f.engine.selectAudioTrack(null); runCurrent()
                assertEquals(0, f.engine.status.value?.currentAudioIndex)
                assertFalse(f.loads.last().playWhenReady)
                f.engine.resume(); runCurrent()
                assertEquals(captions, f.engine.status.value?.currentSubtitleIndex)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `preferred audio language applies to files and provider streams and default stays pinned in Original`() = runTest {
        for (protocol in Protocol.entries) {
            val f = Fixture(this, protocol)
            coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } returns audioTracks
            f.providerAudioTracks = audioTracks.map { com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("version:${it.extractorTrackId}"), it.language, it.label, it.isDefault) }
            try {
                f.start(prefs = StreamPreferences(preferredAudioLanguage = "cs")); runCurrent()
                assertEquals(1, f.engine.status.value?.currentAudioIndex)
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                assertFailsWith<IllegalArgumentException> { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL) }
                f.engine.stop(); runCurrent()
                f.startOnline(StreamPreferences(preferredAudioLanguage = "cs")); runCurrent()
                assertEquals(1, f.engine.status.value?.currentAudioIndex)
                assertEquals(TrackRef("version:9"), f.requests.last().audioTrack)
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                assertTrue(f.requests.last().forceTranscode)
                f.engine.selectAudioTrack(0); runCurrent()
                assertEquals(TrackRef("version:7"), f.requests.last().audioTrack)
                assertTrue(f.requests.last().forceTranscode)
                assertFailsWith<IllegalArgumentException> { f.engine.selectAudioTrack(99) }
                f.engine.playNext(f.movie.copy(id = "next"), StreamPreferences()); runCurrent()
                assertEquals(0, f.engine.status.value?.currentAudioIndex)
                f.engine.stop(); runCurrent()
                f.providerAudioTracks = f.providerAudioTracks.take(1)
                f.startOnline(StreamPreferences(preferredAudioLanguage = "eng")); runCurrent()
                assertNull(f.requests.last().audioTrack, "a matching single audio stream needs no automatic conversion")
                f.engine.selectAudioTrack(0); runCurrent()
                assertTrue(f.requests.last().forceTranscode, "an explicit track choice must still be pinned")
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `explicit audio default during discovery overrides language preference and a new video clears it`() = runTest {
        val f = Fixture(this)
        val discovered = CompletableDeferred<List<LocalAudioTrack>>()
        coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } coAnswers { discovered.await() }
        val starting = async { f.start(prefs = StreamPreferences(preferredAudioLanguage = "cs")) }
        try {
            runCurrent()
            f.engine.selectAudioTrack(null)
            discovered.complete(audioTracks); runCurrent(); starting.await()
            assertEquals(0, f.engine.status.value?.currentAudioIndex)
            f.engine.stop(); runCurrent()
            f.start(prefs = StreamPreferences(preferredAudioLanguage = "cs")); runCurrent()
            assertEquals(1, f.engine.status.value?.currentAudioIndex)
        } finally { discovered.complete(emptyList()); starting.cancel(); f.engine.stop(); runCurrent() }
    }

    @Test fun `audio selection remains responsive while a provider stream is preparing`() = runTest {
        for (protocol in Protocol.entries) {
            val f = Fixture(this, protocol)
            f.providerAudioTracks = audioTracks.map { com.adsamcik.streamferry.source.api.MediaTrack(TrackRef(it.extractorTrackId.toString()), it.language, it.label, it.isDefault) }
            var calls = 0
            coEvery { f.provider.prepare(any()) } coAnswers {
                f.requests += firstArg<PlaybackRequest>()
                if (++calls == 2) awaitCancellation() else Result.success(f.providerSession)
            }
            val starting = async { f.startOnline(StreamPreferences(preferredAudioLanguage = "cs")) }
            try {
                runCurrent()
                assertFalse(starting.isCompleted)
                f.engine.pause()
                f.engine.selectAudioTrack(2); runCurrent(); starting.await()
                assertEquals(2, f.engine.status.value?.currentAudioIndex)
                assertEquals(TrackRef("10"), f.requests.last().audioTrack)
                assertFalse(f.loads.last().playWhenReady)
            } finally { starting.cancel(); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `queued duplicate audio choices finish interrupted startup and duplicate labels stay distinct`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val tracks = audioTracks.map { it.copy(label = "Audio") }
            coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } returns tracks
            val f = Fixture(this, protocol)
            f.providerAudioTracks = tracks.map { com.adsamcik.streamferry.source.api.MediaTrack(TrackRef(it.extractorTrackId.toString()), it.language, it.label, it.isDefault) }
            val ready = CompletableDeferred<Unit>()
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE); runCurrent()
                val loads = f.loads.size
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } coAnswers { ready.await() }
                val seeking = launch { f.engine.seekTo(300) }; runCurrent()
                val first = launch { f.engine.selectAudioTrack(1) }
                val second = launch { f.engine.selectAudioTrack(1) }
                runCurrent()
                assertTrue(seeking.isCompleted && first.isCompleted)
                assertFalse(second.isCompleted, "duplicate audio cancelled startup without replacing it: $source/$protocol")
                ready.complete(Unit); runCurrent(); second.join()
                assertEquals(loads + 1, f.loads.size)
                val status = assertNotNull(f.engine.status.value)
                assertEquals(1, status.currentAudioIndex)
                assertEquals(300L, status.positionSeconds)
                assertEquals(listOf("Audio · Track 1", "Audio · Track 2", "Audio · Track 3"), status.audioTracks.map { it.label })
            } finally {
                ready.complete(Unit); runCurrent(); f.engine.stop(); runCurrent()
                coEvery { anyConstructed<StreamingTranscodeSession>().awaitReady() } returns Unit
            }
        }
    }

    @Test fun `unsupported preferred audio leaves usable playback and manual invalid choices do not tear it down`() = runTest {
        coEvery { LocalSubtitleReader.discoverAudio(any(), any(), any()) } returns audioTracks.map {
            if (it.index == 1) it.copy(unsupportedReason = "Encrypted audio cannot be converted") else it
        }
        val f = Fixture(this)
        try {
            f.start(prefs = StreamPreferences(preferredAudioLanguage = "cs")); runCurrent()
            assertEquals(0, f.engine.status.value?.currentAudioIndex)
            val loads = f.loads.size
            assertFailsWith<PlaybackPreparationException> { f.engine.selectAudioTrack(1) }
            assertFailsWith<IllegalArgumentException> { f.engine.selectAudioTrack(99) }
            assertEquals(loads, f.loads.size)
            assertEquals(0, f.engine.status.value?.currentAudioIndex)
        } finally { f.engine.stop(); runCurrent() }
    }

    private val audioTracks = listOf(
        LocalAudioTrack(0, 7, "7", "eng", "English", true, 0, "audio/mp4a-latm", null, 2, 48_000),
        LocalAudioTrack(1, 9, "9", "ces", "Czech", false, 0, "audio/ac3", null, 6, 48_000),
        LocalAudioTrack(2, 10, "10", "eng", "English commentary", false, 8, "audio/mp4a-latm", null, 2, 48_000),
    )

    @Test fun `embedded and picked subtitles retain selection pause timeline and output across handoff and failed retry for every source`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val embedded = LocalSubtitleTrack(4, "eng", "English", source = LocalSubtitleSource.Embedded(7))
            coEvery { LocalSubtitleReader.discover(any(), any(), any()) } returns listOf(embedded)
            val sidecar = embedded.copy(source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"))
            every { LocalSubtitleReader.external(any(), any()) } returns sidecar
            every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns mockk(relaxed = true)
            val f = Fixture(this, protocol)
            f.providerMediaVersion = "version"
            try {
                f.startSource(source); runCurrent()
                if (source in listOf(SourceCase.FILE, SourceCase.CONTENT_URI)) f.engine.selectSubtitleTrack(4)
                else f.engine.attachSubtitle("content://subtitles/42")
                runCurrent()
                val selected = assertNotNull(f.engine.status.value?.currentSubtitleIndex)
                assertEquals("On-device transcode", f.engine.status.value?.streamMode)
                assertEquals(LocalTranscodeMode.ON_DEVICE, f.engine.status.value?.localTranscodeMode)
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.engine.pause(); runCurrent()
                f.engine.seekTo(450); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                val alternate = Protocol.entries.first { it != protocol }
                f.continueSource(source, alternate, 450, continuation); runCurrent()
                assertEquals(selected, f.engine.status.value?.currentSubtitleIndex)
                assertEquals(450L, f.engine.status.value?.positionSeconds)
                assertFalse(f.loads.last().playWhenReady)
                if (source in listOf(SourceCase.PROVIDER_FILE, SourceCase.PROVIDER_HLS)) {
                    assertTrue(f.requests.last().subtitlesDisabled, "external subtitles must not enable a server default")
                    assertNull(f.requests.last().subtitleTrack)
                    assertEquals("version", f.requests.last().mediaVersion)
                }
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, false)); runCurrent()
                val returning = assertNotNull(f.engine.beginManualProtocolSwitch())
                coEvery { f.target.connect(any()) } throws IllegalStateException("TV unavailable")
                assertFailsWith<IllegalStateException> { f.continueSource(source, protocol, 450, returning) }
                val retry = assertNotNull(f.engine.continuationForExplicitRetry())
                f.engine.stop(); runCurrent()
                coEvery { f.target.connect(any()) } just Runs
                f.continueSource(source, protocol, 450, retry); runCurrent()
                assertEquals(selected, f.engine.status.value?.currentSubtitleIndex)
                assertEquals("English", f.engine.status.value?.subtitleTracks?.single()?.label)
                assertFalse(f.loads.last().playWhenReady)
                f.engine.selectSubtitleTrack(null); runCurrent()
                assertNull(f.engine.status.value?.currentSubtitleIndex)
                assertFalse(f.loads.last().playWhenReady)
                assertEquals(450L, f.engine.status.value?.positionSeconds)
                f.engine.stop(); runCurrent()
                coEvery { LocalSubtitleReader.discover(any(), any(), any()) } returns emptyList()
                f.startSource(source); runCurrent()
                assertTrue(assertNotNull(f.engine.status.value).subtitleTracks.isEmpty(), "subtitle file leaked into a different video")
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `autoplay next clears a picked subtitle and previous media version`() = runTest {
        val f = Fixture(this)
        f.providerMediaVersion = "version"
        every { LocalSubtitleReader.external(any(), any()) } returns LocalSubtitleTrack(
            0, "eng", "English", source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"),
        )
        every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns mockk(relaxed = true)
        try {
            f.startOnline(); runCurrent()
            f.engine.attachSubtitle("content://subtitles/42"); runCurrent()
            assertNotNull(f.engine.status.value?.currentSubtitleIndex)
            f.providerMediaVersion = "next-version"
            assertTrue(f.engine.playNext(f.movie.copy(id = "next"), StreamPreferences()))
            runCurrent()
            assertNull(f.requests.first { it.media.nativeId == "next" }.mediaVersion)
            assertTrue(assertNotNull(f.engine.status.value).subtitleTracks.isEmpty())
            assertNull(f.engine.status.value?.currentSubtitleIndex)
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `picking a subtitle during initial discovery preserves the file and incoming source tracks`() = runTest {
        for (source in listOf(SourceCase.FILE, SourceCase.PROVIDER_FILE)) {
            val f = Fixture(this)
            var discoveries = 0
            val embedded = LocalSubtitleTrack(0, "eng", "Embedded English", source = LocalSubtitleSource.Embedded(7))
            coEvery { LocalSubtitleReader.discover(any(), any(), any()) } coAnswers {
                if (++discoveries == 1) awaitCancellation() else listOf(embedded)
            }
            f.providerSubtitleTracks = listOf(com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("7"), "eng", "Provider English"))
            var preparations = 0
            coEvery { f.provider.prepare(any()) } coAnswers {
                f.requests += firstArg<PlaybackRequest>()
                if (++preparations == 1) awaitCancellation() else Result.success(f.providerSession)
            }
            every { LocalSubtitleReader.external(any(), any()) } returns embedded.copy(
                label = "Picked captions", source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"),
            )
            every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns mockk(relaxed = true)
            val starting = async { f.startSource(source) }
            try {
                runCurrent()
                assertFalse(starting.isCompleted)
                f.engine.attachSubtitle("content://subtitles/42"); runCurrent()
                starting.await()
                val status = assertNotNull(f.engine.status.value)
                assertEquals("Picked captions", status.subtitleTracks.single { it.index == status.currentSubtitleIndex }.label)
                assertEquals(2, status.subtitleTracks.size)
                assertEquals(2, status.subtitleTracks.map { it.index }.distinct().size)
                if (source == SourceCase.PROVIDER_FILE) {
                    f.engine.selectSubtitleTrack(status.subtitleTracks.single { it.label == "Provider English" }.index)
                    runCurrent()
                    assertEquals(TrackRef("7"), f.requests.last().subtitleTrack)
                }
            } finally { starting.cancel(); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `retry during subtitle discovery retains picked captions and discovers embedded tracks`() = runTest {
        for (protocol in Protocol.entries) {
            val f = Fixture(this, protocol)
            var discoveries = 0
            val embedded = LocalSubtitleTrack(0, "eng", "Embedded English", source = LocalSubtitleSource.Embedded(7))
            coEvery { LocalSubtitleReader.discover(any(), any(), any()) } coAnswers {
                if (++discoveries < 3) awaitCancellation() else listOf(embedded)
            }
            every { LocalSubtitleReader.external(any(), any()) } returns embedded.copy(
                label = "Picked captions", source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"),
            )
            every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns mockk(relaxed = true)
            val starting = async { runCatching { f.startSource(SourceCase.FILE) } }
            var attaching: kotlinx.coroutines.Deferred<Result<Unit>>? = null
            try {
                runCurrent()
                attaching = async { runCatching { f.engine.attachSubtitle("content://subtitles/42") } }
                runCurrent()
                assertEquals(2, discoveries)
                val continuation = assertNotNull(f.engine.continuationForExplicitRetry())
                f.engine.stop(); runCurrent()
                f.continueSource(SourceCase.FILE, protocol, 123, continuation)
                runCurrent()
                val status = assertNotNull(f.engine.status.value)
                assertEquals(3, discoveries)
                assertEquals(2, status.subtitleTracks.size)
                assertEquals("Picked captions", status.subtitleTracks.single { it.index == status.currentSubtitleIndex }.label)
            } finally { attaching?.cancel(); starting.cancel(); f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `explicit subtitle Off during discovery overrides a pending preferred language`() = runTest {
        val f = Fixture(this)
        val discovered = CompletableDeferred<List<LocalSubtitleTrack>>()
        coEvery { LocalSubtitleReader.discover(any(), any(), any()) } coAnswers { discovered.await() }
        val starting = async { f.start(prefs = StreamPreferences(preferredSubtitleLanguage = "eng")) }
        try {
            runCurrent()
            f.engine.selectSubtitleTrack(null)
            discovered.complete(listOf(LocalSubtitleTrack(0, "eng", "English", source = LocalSubtitleSource.Embedded(7))))
            runCurrent(); starting.await()
            assertNull(f.engine.status.value?.currentSubtitleIndex)
            assertEquals("On-device transcode", f.engine.status.value?.streamMode, "Off must strip embedded subtitle packets")
        } finally { discovered.complete(emptyList()); starting.cancel(); f.engine.stop(); runCurrent() }
    }

    @Test fun `unsupported or missing local subtitle selection leaves usable video untouched`() = runTest {
        coEvery { LocalSubtitleReader.discover(any(), any(), any()) } returns listOf(
            LocalSubtitleTrack(4, "eng", "English", source = LocalSubtitleSource.Embedded(7), unsupportedReason = "Unsupported subtitle format"),
        )
        val f = Fixture(this)
        try {
            f.start(prefs = StreamPreferences(preferredSubtitleLanguage = "eng")); runCurrent()
            val loadCount = f.loads.size
            assertNull(f.engine.status.value?.currentSubtitleIndex)
            assertFalse(assertNotNull(f.engine.status.value).isTerminal)
            assertFailsWith<PlaybackPreparationException> { f.engine.selectSubtitleTrack(4) }
            assertFailsWith<IllegalArgumentException> { f.engine.selectSubtitleTrack(99) }
            assertEquals(loadCount, f.loads.size)
            assertNull(f.engine.status.value?.currentSubtitleIndex)
        } finally { f.engine.stop(); runCurrent() }
    }

    @Test fun `slow subtitle file validation does not block Pause or Stop or attach to a later video`() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val track = LocalSubtitleTrack(0, "eng", "English", source = LocalSubtitleSource.External("content://subtitles/42", "text/vtt"))
        every { LocalSubtitleReader.external(any(), any()) } returns track
        every { LocalSubtitleReader.open(any(), any(), any(), any()) } returns object : com.adsamcik.streamferry.data.subtitle.SubtitleCueSource {
            override fun cuesAt(sourceTimeUs: Long): List<androidx.media3.common.text.Cue> {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return emptyList()
            }
            override fun close() = Unit
        }
        val f = Fixture(this, dispatcher = Dispatchers.IO)
        var attaching: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            f.startSource(SourceCase.FILE)
            val pending = async(Dispatchers.IO) { runCatching { f.engine.attachSubtitle("content://subtitles/42") } }
            attaching = pending
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            withContext(Dispatchers.IO) { withTimeout(5_000) { f.engine.pause(); f.engine.stop(); f.startSource(SourceCase.FILE) } }
            coVerify { f.target.pause() }
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { withTimeout(5_000) { pending.await().isFailure } })
            assertTrue(assertNotNull(f.engine.status.value).subtitleTracks.isEmpty())
            assertNull(f.engine.status.value?.currentSubtitleIndex)
        } finally {
            release.countDown(); attaching?.cancel()
            f.engine.stop()
        }
    }

    @Test fun `provider subtitle selection forces burn in even with Original output on both protocols`() = runTest {
        for (protocol in Protocol.entries) {
            val f = Fixture(this, protocol)
            f.providerMediaVersion = "version"
            f.providerSubtitleTracks = listOf(com.adsamcik.streamferry.source.api.MediaTrack(TrackRef("version:7"), "eng", "English"))
            try {
                f.startOnline(); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ORIGINAL); runCurrent()
                f.engine.selectSubtitleTrack(0); runCurrent()
                assertEquals(TrackRef("version:7"), f.requests.last().subtitleTrack)
                assertEquals("version", f.requests.last().mediaVersion)
                assertTrue(f.requests.last().forceTranscode)
                assertTrue(f.requests.last().allowSubtitleBurnIn)
                assertFalse(f.requests.last().subtitlesDisabled)
                f.engine.selectSubtitleTrack(null); runCurrent()
                assertTrue(f.requests.last().subtitlesDisabled)
                assertNull(f.requests.last().subtitleTrack)
                assertEquals("version", f.requests.last().mediaVersion, "Off must retain the version independently from the subtitle ref")
            } finally { f.engine.stop(); runCurrent() }
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

    @Test fun `cancelled renderer startup retains retry position pause and output choices for every source`() = runTest {
        for (source in SourceCase.entries) {
            val f = Fixture(this, Protocol.DLNA)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE)
                f.engine.selectPreferredCodec("hevc")
                f.engine.selectMaxVideoHeight(480)
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(77, true)); runCurrent()
                f.engine.pause()
                val continuation = assertNotNull(f.engine.beginManualProtocolSwitch())
                coEvery { f.target.load(any(), any(), any(), any(), any(), any()) } throws CancellationException("TV dropped startup")
                assertFailsWith<CancellationException> { f.continueSource(source, Protocol.DLNA, 200, continuation) }
                val retry = assertNotNull(f.engine.continuationForExplicitRetry(), "$source discarded the failed start")
                assertEquals(200L, retry.positionSeconds)
                assertEquals(false, retry.playbackChoices?.playWhenReady)
                assertEquals(LocalTranscodeMode.ON_DEVICE, retry.playbackChoices?.transcodeMode)
                assertEquals(VideoCodec.HEVC, retry.playbackChoices?.localCodec)
                assertEquals(480, retry.playbackChoices?.maxVideoHeight)
                assertEquals(PlaybackPhase.FAILED, f.engine.recoverySnapshot().phase)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `renderer stop before the first frame surfaces failure for every source`() = runTest {
        for (source in SourceCase.entries) {
            val f = Fixture(this, Protocol.DLNA)
            try {
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.Stopped); runCurrent()
                // Any permitted fallback must also reject the TV stopping before its first frame.
                repeat(8) {
                    if (f.engine.status.value?.isTerminal != true) {
                        f.events.emit(PlaybackTargetEvent.Stopped); runCurrent()
                    }
                }
                val failed = assertNotNull(f.engine.status.value, "$source silently stopped instead of reporting startup failure")
                assertTrue(failed.isTerminal, "$source kept waiting after the TV rejected playback")
                assertEquals(PlaybackFailureStage.FIRST_FRAME, failed.attemptHistory.last().failureStage)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `automatic protocol fallback remains available after renderer format recovery for every source`() = runTest {
        for (source in SourceCase.entries) {
            val f = Fixture(this, Protocol.CAST)
            try {
                f.startSource(source); runCurrent()
                f.engine.selectMaxVideoHeight(2160); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(123, true)); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(200, true)); runCurrent()
                f.engine.pause(); runCurrent()
                val checkpoint = assertNotNull(f.engine.status.value).recoveryPositionSeconds
                repeat(8) {
                    if (f.engine.status.value?.isTerminal != true) {
                        f.events.emit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "Cast rejected the format"))
                        runCurrent()
                    }
                }
                val failed = assertNotNull(f.engine.status.value)
                assertTrue(failed.isTerminal, "$source did not exhaust renderer format recovery")
                val attempt = failed.attemptHistory.last()
                val continuation = assertNotNull(f.engine.reserveAlternateProtocolContinuation(
                    hasAlternateProtocol = true,
                    failureStage = assertNotNull(attempt.failureStage),
                    failureCause = assertNotNull(attempt.failureCause),
                    sameEndpointRecoveryExhausted = true,
                ), "$source lost the protocol fallback after format retries")
                assertEquals(checkpoint, continuation.positionSeconds)
                f.continueSource(source, Protocol.DLNA, checkpoint, continuation); runCurrent()
                assertFalse(f.loads.last().playWhenReady, "$source lost pause on automatic handoff")
                assertEquals(failed.maxVideoHeight, f.engine.status.value?.maxVideoHeight)
                assertTrue(f.engine.recoverySnapshot().alternateProtocolReserved)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `renderer callbacks cannot revive terminal failure or erase its message for any source or protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                repeat(8) {
                    if (f.engine.status.value?.isTerminal != true) {
                        f.events.emit(PlaybackTargetEvent.Error(com.adsamcik.streamferry.domain.PlaybackFailureKind.NETWORK, "TV disconnected"))
                        runCurrent()
                    }
                }
                val failure = assertNotNull(f.engine.status.value)
                assertTrue(failure.isTerminal, "$source/$protocol did not reach a bounded failure")
                for (event in listOf(
                    PlaybackTargetEvent.Connected, PlaybackTargetEvent.StatusChanged(0, true),
                    PlaybackTargetEvent.BufferingChanged(true), PlaybackTargetEvent.Disconnected,
                    PlaybackTargetEvent.Stopped, PlaybackTargetEvent.Ended,
                )) {
                    f.events.emit(event); runCurrent()
                    assertEquals(failure, f.engine.status.value, "$source/$protocol accepted late $event")
                }
                advanceTimeBy(15_000); runCurrent()
                assertEquals(failure.errorMessage, f.engine.status.value?.errorMessage, "monitor erased the terminal error")
                f.engine.stop(); runCurrent()
                f.startSource(source); runCurrent()
                f.events.emit(PlaybackTargetEvent.StatusChanged(f.loads.last().position, true)); runCurrent()
                assertEquals(PlaybackPhase.PLAYING, f.engine.status.value?.phase)
                assertNull(f.engine.status.value?.errorMessage)
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `buffering at a nonzero resume position cannot disable first frame recovery for any source or protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                val loadCount = f.loads.size
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true))
                f.events.emit(PlaybackTargetEvent.StatusChanged(f.loads.last().position.coerceAtLeast(1), false))
                runCurrent()
                advanceTimeBy(StartupWatchdog.GRACE_MS); runCurrent()
                assertTrue(f.engine.recoverySnapshot().attempts.any { it.failureStage == PlaybackFailureStage.FIRST_FRAME },
                    "$source/$protocol accepted a buffering resume position as first-frame evidence")
                assertTrue(f.loads.size > loadCount || f.engine.status.value?.isTerminal == true,
                    "$source/$protocol neither recovered nor surfaced the stalled load")
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `paused startup is allowed to wait but Resume still requires real playback for every source and protocol`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            try {
                f.startSource(source); runCurrent()
                f.engine.pause(); runCurrent()
                val position = f.loads.last().position.coerceAtLeast(1)
                f.events.emit(PlaybackTargetEvent.StatusChanged(position, false)); runCurrent()
                val loadCount = f.loads.size
                advanceTimeBy(StartupWatchdog.GRACE_MS * 2); runCurrent()
                assertEquals(loadCount, f.loads.size)
                assertFalse(assertNotNull(f.engine.status.value).isTerminal)
                f.engine.resume(); runCurrent()
                f.events.emit(PlaybackTargetEvent.BufferingChanged(true))
                f.events.emit(PlaybackTargetEvent.StatusChanged(f.loads.last().position.coerceAtLeast(1), false)); runCurrent()
                advanceTimeBy(StartupWatchdog.GRACE_MS); runCurrent()
                assertTrue(f.engine.recoverySnapshot().attempts.any { it.failureStage == PlaybackFailureStage.FIRST_FRAME },
                    "$source/$protocol resumed without retaining the first-frame deadline")
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    @Test fun `final Cast teardown bypasses a hung media Stop while DLNA retains Stop before disconnect for every source`() = runTest {
        for (protocol in Protocol.entries) for (source in SourceCase.entries) {
            val f = Fixture(this, protocol)
            val commands = mutableListOf<String>()
            try {
                f.startSource(source); runCurrent()
                coEvery { f.target.stop() } coAnswers {
                    commands += "stop"
                    if (protocol == Protocol.CAST) awaitCancellation()
                }
                coEvery { f.target.disconnect() } answers { commands += "disconnect" }
                val stopping = async { f.engine.stop() }; runCurrent()
                assertTrue(stopping.isCompleted, "$source/$protocol waited for the hung media channel")
                stopping.await()
                assertEquals(if (protocol == Protocol.CAST) listOf("disconnect") else listOf("stop", "disconnect"), commands)
                assertNull(f.engine.status.value)
            } finally {
                coEvery { f.target.stop() } returns Unit
                f.engine.stop(); runCurrent()
            }
        }
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
                f.events.emit(PlaybackTargetEvent.Stopped)
                f.events.emit(PlaybackTargetEvent.Ended)
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
