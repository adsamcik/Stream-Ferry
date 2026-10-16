package com.adsamcik.streamferry.playback

import android.content.Context
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.LocalTranscodeMode
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartPath
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.PlaybackProvider
import io.mockk.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }

    @AfterTest fun releaseLocalProbe() = unmockkObject(LocalMediaProbe)

    private class Fixture(scope: TestScope, protocol: Protocol = Protocol.CAST) {
        val events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
        val target = mockk<PlaybackTargetController>(relaxed = true)
        val coordinator = mockk<DefaultPlaybackSessionCoordinator>(relaxed = true)
        private val provider = mockk<PlaybackProvider>(relaxed = true)
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

    @Test fun `unsupported local source and receiver keep original playable with a reason and no transcode reload`() = runTest {
        val cases = listOf(
            compatibleProfile.copy(isHdr = true, bitDepth = 10) to Protocol.CAST,
            null to Protocol.CAST,
            compatibleProfile to Protocol.DLNA,
        )
        for ((profile, protocol) in cases) {
            every { LocalMediaProbe.probe(any(), any(), any()) } returns profile
            val f = Fixture(this, protocol)
            try {
                f.start(); runCurrent()
                val status = assertNotNull(f.engine.status.value)
                assertEquals("Direct play", status.streamMode)
                assertNotNull(status.localTranscodeUnavailableReason)
                assertTrue(status.availableVideoCodecs.isEmpty())
                assertFailsWith<PlaybackPreparationException> {
                    f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE)
                }
                assertEquals("Direct play", f.engine.status.value?.streamMode)
                coVerify(exactly = 0) { f.coordinator.startClientTranscodeAndBuildUrl(any(), any()) }
                coVerify(exactly = 1) { f.target.load(ORIGINAL_URL, any(), "Movie", 3600, 123, true) }
            } finally { f.engine.stop(); runCurrent() }
        }
    }

    private companion object {
        const val ORIGINAL_URL = "http://127.0.0.1/session/test/stream"
        const val TRANSCODE_URL = "http://127.0.0.1/session/test/transcode.m3u8"
        val compatibleProfile = MediaProfile(
            "mp4", "h264", widthPx = 1920, heightPx = 1080, audioCodec = "aac",
        )
    }
}
