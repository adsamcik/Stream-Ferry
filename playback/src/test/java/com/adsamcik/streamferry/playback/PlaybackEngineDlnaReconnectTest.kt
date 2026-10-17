package com.adsamcik.streamferry.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.LocalTranscodeMode
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.data.transcode.OnDeviceTranscoder
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.playback.proxy.ProxyForegroundStartPath
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.source.api.PlaybackDescriptor
import com.adsamcik.streamferry.source.api.PlaybackProvider
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import com.adsamcik.streamferry.source.api.StreamDescriptor
import io.mockk.*
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses the real TS session and encoder callback, rather than constructor-mocking reconnect wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackEngineDlnaReconnectTest {
    private val profile = MediaProfile("mp4", "h264", audioCodec = "aac", widthPx = 640, heightPx = 360)
    private data class Load(val position: Long, val duration: Long?, val playing: Boolean)

    @Before fun mockProbe() {
        mockkObject(LocalMediaProbe)
        every { LocalMediaProbe.probe(any(), any(), any()) } returns profile
        every { LocalMediaProbe.isByteSeekable(any(), any()) } returns true
        every { LocalMediaProbe.probeDurationSecondsExact(any(), any()) } returns 3600.0
    }

    @After fun releaseMocks() = unmockkAll()

    @Test fun `local DLNA reconnect queries transport before playing event and preserves source offset`() = runTest {
        proveReconnect(providerSource = false)
    }

    @Test fun `provider DLNA reconnect queries transport before playing event and preserves source offset`() = runTest {
        proveReconnect(providerSource = true)
    }

    @Test fun `Stop while reconnect queries the TV cannot start a replacement stream`() = runTest {
        val f = Fixture(this)
        try {
            finish(launch { f.start(providerSource = false) })
            finish(launch { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE) })
            val source = f.sources.last()
            val reader = assertNotNull(source.openProgressive())
            reader.readNBytes(2 * 1024 * 1024 + 6_016)
            reader.close()
            val previousLoads = f.loads.size
            f.freshQuery = CompletableDeferred()
            val reconnectReader = assertNotNull(source.openProgressive())
            until { f.queryStarted.isCompleted }
            val stopping = launch { f.engine.stop() }
            runCurrent()
            f.freshQuery!!.complete(true)
            finish(stopping)
            assertEquals(previousLoads, f.loads.size)
            assertEquals(-1, reconnectReader.read())
            reconnectReader.close()
        } finally {
            finish(launch { f.engine.stop() })
        }
    }

    private suspend fun TestScope.proveReconnect(providerSource: Boolean) {
        // True models TV Play reopening HTTP before its PLAYING poll. False models paused prefetch;
        // unknown must retain the last confirmed intent rather than infer Play from the HTTP GET.
        for (freshPlaying in listOf(true, false, null)) {
            val f = Fixture(this)
            try {
                finish(launch { f.start(providerSource) })
                finish(launch { f.engine.selectLocalTranscodeMode(LocalTranscodeMode.ON_DEVICE) })
                assertEquals("On-device transcode", f.engine.status.value?.streamMode)
                val oldSource = assertNotNull(f.sources.lastOrNull())
                val reader = assertNotNull(oldSource.openProgressive())
                val count = reader.readNBytes(2 * 1024 * 1024 + 6_016).size
                assertEquals(2 * 1024 * 1024 + 6_016, count)
                reader.close()

                // Establish real playback before reporting a native Pause; otherwise the engine
                // correctly keeps initial autoplay intent while the first frame is unconfirmed.
                f.events.emit(PlaybackTargetEvent.StatusChanged(0, true)); runCurrent()
                val cachedPlaying = freshPlaying == false
                f.events.emit(PlaybackTargetEvent.StatusChanged(77, cachedPlaying))
                runCurrent()
                assertEquals(200L, f.engine.status.value?.positionSeconds)
                f.freshPlaying = freshPlaying
                val previousLoads = f.loads.size
                val reconnectReader = assertNotNull(oldSource.openProgressive())
                // No PLAYING event is emitted between reopening HTTP and the replacement load.
                until { f.loads.size > previousLoads }
                coVerify(exactly = 1) { f.target.readIsPlaying() }
                assertEquals(freshPlaying ?: cachedPlaying, f.loads.last().playing)
                assertEquals(0L, f.loads.last().position)
                assertEquals(3400L, f.loads.last().duration)
                assertEquals(200_000L, f.encoderStarts.last())
                assertEquals(200L, f.engine.status.value?.positionSeconds)
                // The old HTTP request is retired by replacing the stream, so it cannot hang a TV.
                assertEquals(-1, reconnectReader.read())
                reconnectReader.close()
            } finally {
                finish(launch { f.engine.stop() })
            }
        }
    }

    /** Drive only ready engine work; let real encoder IO run without advancing startup timeouts. */
    private suspend fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            runCurrent()
            if (condition()) break
            assertTrue(System.nanoTime() < deadline, "engine did not complete the reconnect")
            // Keep this test driver in charge of virtual time while the production-owned IO
            // encoder runs. Suspending onto IO here lets runTest jump to the startup deadline.
            Thread.sleep(5)
        }
        runCurrent()
    }

    private suspend fun TestScope.finish(job: Job) {
        until { job.isCompleted }
        assertFalse(job.isCancelled, "engine operation failed or was cancelled")
    }

    private inner class Fixture(scope: TestScope) {
        val events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
        val target = mockk<PlaybackTargetController>(relaxed = true)
        val sources = CopyOnWriteArrayList<ClientTranscodeSource>()
        val encoderStarts = CopyOnWriteArrayList<Long>()
        val loads = mutableListOf<Load>()
        var freshPlaying: Boolean? = null
        var freshQuery: CompletableDeferred<Boolean?>? = null
        val queryStarted = CompletableDeferred<Unit>()
        val engine: PlaybackEngine
        private val selected = DiscoveredTarget(
            "tv", "TV", Protocol.DLNA,
            TargetCapabilities(Protocol.DLNA, supportedContainers = setOf("mp4"),
                supportedVideoCodecs = setOf("h264"), supportedAudioCodecs = setOf("aac")), null,
        )
        private val movie = MediaItem("movie", "Movie", null, 3600, null, 123, false)

        init {
            val coordinator = mockk<DefaultPlaybackSessionCoordinator>(relaxed = true)
            val provider = mockk<PlaybackProvider>(relaxed = true)
            val providerSession = mockk<ProviderPlaybackSession>(relaxed = true)
            val session = SessionRegistry().create("video/mp4", false, 100_000)
            every { target.events } returns events
            every { target.protocol } returns Protocol.DLNA
            coEvery { target.readCurrentVolume() } returns 0.1f
            coEvery { target.readIsPlaying() } coAnswers {
                queryStarted.complete(Unit)
                freshQuery?.await() ?: freshPlaying
            }
            coEvery { target.load(any(), any(), any(), any(), any(), any()) } answers {
                loads += Load(arg(4), arg(3), arg(5))
            }
            every { coordinator.active } returns MutableStateFlow(session)
            coEvery { coordinator.startLocalAndBuildUrl(any(), any(), any()) } returns (session to "http://phone/original")
            coEvery { coordinator.startAndBuildUrl(any(), any(), any(), any()) } returns (session to "http://phone/original")
            coEvery { coordinator.prepareTranscodeInput(any(), any(), any(), any()) } returns "http://phone/input"
            coEvery { coordinator.startClientTranscodeAndBuildUrl(any(), any()) } answers {
                sources += firstArg<ClientTranscodeSource>()
                session to "http://phone/transcode/${sources.size}"
            }
            every { providerSession.descriptor } returns PlaybackDescriptor(
                movie.ref, profile, 3600,
                stream = StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 100_000),
            )
            coEvery { providerSession.mediaSegments() } returns emptyList()
            coEvery { provider.prepare(any()) } returns Result.success(providerSession)
            val proxy = mockk<LocalProxyServer>(relaxed = true)
            every { proxy.stop() } answers { sources.lastOrNull()?.release(); Unit }
            val service = mockk<PlaybackServiceController>(relaxed = true)
            every { service.isForegroundActive() } returns true
            coEvery { service.startAndAwaitForegrounded(any()) } returns
                ForegroundServiceStartResult.Foregrounded(ProxyForegroundStartPath.START_SERVICE)
            val network = mockk<NetworkInfoProvider>()
            every { network.lanIpv4() } returns "127.0.0.1"
            val transcoder = mockk<OnDeviceTranscoder>()
            every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
            coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                encoderStarts += arg<Long>(3)
                val muxer = arg<Muxer.Factory>(2).create("unused")
                val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(640).setHeight(360)
                    .setInitializationData(listOf(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e),
                        byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x38, 0x80.toByte()))).build()
                val video = muxer.addTrack(format)
                val frame = ByteArray(3 * 1024 * 1024) { 1 }.apply {
                    this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1; this[4] = 0x65
                }
                muxer.writeSampleData(video, ByteBuffer.wrap(frame), BufferInfo(0, frame.size, C.BUFFER_FLAG_KEY_FRAME))
                awaitCancellation()
            }
            engine = PlaybackEngine(
                provider, coordinator, proxy, network, service, mockk(relaxed = true),
                RuntimeEnvironment.getApplication(), transcoder,
                { DeviceEncodeCapabilities(ResolutionTier.FHD_1080P, ResolutionTier.HD_720P) },
                mockk(relaxed = true), clock = { scope.testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(scope.testScheduler), elapsedRealtime = { scope.testScheduler.currentTime },
            )
        }

        suspend fun start(providerSource: Boolean) {
            if (providerSource) engine.play(movie, target, selected, StreamPreferences(maxVideoHeight = 720))
            else engine.playLocal("/download/movie.mp4", "video/mp4", "Movie", 3600,
                resumePositionSeconds = 123, allowClientTranscode = false, target = target, selectedTarget = selected)
        }
    }
}
