package com.adsamcik.streamferry.data.transcode

import android.os.Looper
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Composition
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.effect.Presentation
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.transcode.AudioCodec
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class OnDeviceTranscoderTest {
    private val startedItems = LinkedBlockingQueue<EditedMediaItem>()
    private val startedCompositions = LinkedBlockingQueue<Composition>()
    private val transformer = mockk<Transformer>(relaxed = true)
    private lateinit var transcoder: OnDeviceTranscoder
    private val target = TranscodeTarget(
        VideoCodec.H264, AudioCodec.AAC, StreamContainer.HLS_FMP4, ResolutionTier.HD_720P, 30,
    )

    @Before
    fun setUp() {
        mockkConstructor(Transformer.Builder::class)
        mockkObject(LocalMediaProbe)
        every { LocalMediaProbe.probe(any(), any(), any()) } returns MediaProfile(
            container = "mp4", videoCodec = "h264", audioCodec = "aac", widthPx = 1280, heightPx = 720,
        )
        every { anyConstructed<Transformer.Builder>().setVideoMimeType(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().setAudioMimeType(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().setEncoderFactory(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().setMuxerFactory(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().setLooper(any<Looper>()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().setAssetLoaderFactory(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().addListener(any()) } answers { self as Transformer.Builder }
        every { anyConstructed<Transformer.Builder>().build() } returns transformer
        every { transformer.start(any<EditedMediaItem>(), any()) } answers {
            startedItems.put(firstArg())
        }
        every { transformer.start(any<Composition>(), any()) } answers {
            startedCompositions.put(firstArg())
        }
        transcoder = OnDeviceTranscoder(RuntimeEnvironment.getApplication(), mockk<DiagnosticSink>(relaxed = true))
    }

    @After
    fun tearDown() {
        if (::transcoder.isInitialized) transcoder.release()
        unmockkAll()
    }

    @Test
    fun `downloaded filesystem path reaches exporter as an encoded file URI`() = runBlocking {
        val path = "/data/user/0/com.adsamcik.streamferry/files/A video #1.mp4"
        val uri = assertNotNull(startAndCancel(path).mediaItem.localConfiguration?.uri)
        assertEquals("file", uri.scheme)
        // java.io.File follows the host filesystem in Robolectric. Windows expands /data to C:\data,
        // and Android Uri adds its leading slash; compare the decoded absolute filename portably.
        assertEquals(File(path).absolutePath.trimStart('/'), uri.path?.trimStart('/'))
        assertTrue(uri.encodedPath?.contains("%23") == true)
        assertTrue(uri.encodedPath?.contains("%20") == true)
    }

    @Test
    fun `gallery content URI remains unchanged`() = runBlocking {
        val uri = "content://media/external/video/media/42"
        assertEquals(uri, startAndCancel(uri).mediaItem.localConfiguration?.uri.toString())
    }

    @Test
    fun `nonzero local and remote restarts accept genuinely unseekable source timelines`() = runBlocking {
        val sources = listOf(
            "file:///download/video.ts" to "video/mp2t",
            "content://media/external/video/media/42" to "video/mp2t",
            "https://source.test/video.ts" to "video/mp2t",
            "https://source.test/playlist.m3u8" to "application/x-mpegURL",
        )
        for ((uri, mime) in sources) {
            val item = startAndCancel(uri, startMs = 60_000, sourceMimeType = mime).mediaItem
            val child = mockk<MediaSource>(relaxed = true)
            every { child.mediaItem } returns item
            every { child.prepareSource(any(), any<PlayerId>(), any<BandwidthMeter>()) } answers {
                firstArg<MediaSource.MediaSourceCaller>().onSourceInfoRefreshed(
                    child, SinglePeriodTimeline(120_000_000, false, false, false, null, item),
                )
            }
            // Exercise the real Media3 timeline guard, not a mock of clipping configuration.
            val clipped = ClippingMediaSource.Builder(child)
                .setClippingConfiguration(item.clippingConfiguration)
                .build()
            var timeline: Timeline? = null
            val caller = MediaSource.MediaSourceCaller { _, refreshed -> timeline = refreshed }
            try {
                clipped.prepareSource(caller, PlayerId.UNSET, mockk<BandwidthMeter>(relaxed = true))
                clipped.maybeThrowSourceInfoRefreshError()
                val window = assertNotNull(timeline, "unseekable $uri was rejected")
                    .getWindow(0, Timeline.Window())
                assertEquals(60_000_000L, window.durationUs)
                assertEquals(60_000_000L, window.positionInFirstPeriodUs)
            } finally {
                clipped.releaseSource(caller)
            }
        }
    }

    @Test
    fun `a small source keeps its height instead of being upscaled to the output ceiling`() = runBlocking {
        every { LocalMediaProbe.probe(any(), any(), any()) } returns MediaProfile(
            container = "mp4", videoCodec = "h264", audioCodec = "aac", widthPx = 640, heightPx = 360,
        )
        val effect = startAndCancel("file:///data/local/tmp/small.mp4").effects.videoEffects.single() as Presentation
        val size = effect.configure(640, 360)
        assertEquals(640, size.width)
        assertEquals(360, size.height)
    }

    @Test
    fun `successful cancellation without listener callback releases next export`() = runBlocking {
        // The mock cancel returns normally and sends no listener callback, matching Media3 cancel.
        startAndCancel("file:///data/local/tmp/first.mp4")
        val second = startAndCancel("file:///data/local/tmp/second.mp4")
        assertEquals("/data/local/tmp/second.mp4", second.mediaItem.localConfiguration?.uri?.path)
    }

    @Test
    fun `unsupported URI scheme is rejected before any export`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            transcoder.transcodeStream("ftp://example.test/video.mp4", target, streamMuxer)
        }
        assertTrue(startedItems.isEmpty())
    }

    @Test
    fun `remote HLS reaches continuous export with its MIME custom muxer and SDR tone mapping`() = runBlocking {
        val owner = Any()
        val failure = CompletableDeferred<Throwable?>()
        val export = launch(Dispatchers.Default) {
            failure.complete(runCatching {
                transcoder.transcodeStream(
                    "http://127.0.0.1:1234/stream", target, streamMuxer,
                    sourceMimeType = "application/x-mpegURL", owner = owner,
                )
            }.exceptionOrNull())
        }
        try {
            val composition = assertNotNull(startedCompositions.poll(5, TimeUnit.SECONDS))
            assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, composition.hdrMode)
            val item = composition.sequences.single().editedMediaItems.single().mediaItem
            assertEquals("http://127.0.0.1:1234/stream", item.localConfiguration?.uri.toString())
            assertEquals("application/x-mpegURL", item.localConfiguration?.mimeType)
            verify { anyConstructed<Transformer.Builder>().setMuxerFactory(streamMuxer) }
            assertTrue(transcoder.cancelOwnedInFlightAndAwait(owner))
            assertIs<CancellationException>(withTimeout(5_000) { failure.await() })
            export.join()
        } finally {
            export.cancel()
        }
    }

    @Test
    fun `HDR local source uses the same SDR conversion composition`() = runBlocking {
        every { LocalMediaProbe.probe(any(), any(), any()) } returns MediaProfile(
            container = "mp4", videoCodec = "hevc", audioCodec = "aac", isHdr = true, bitDepth = 10,
        )
        val owner = Any()
        val export = launch(Dispatchers.Default) {
            runCatching {
                transcoder.transcodeStream("content://media/video/10", target, streamMuxer, owner = owner)
            }
        }
        try {
            val composition = assertNotNull(startedCompositions.poll(5, TimeUnit.SECONDS))
            assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, composition.hdrMode)
            assertTrue(transcoder.cancelOwnedInFlightAndAwait(owner))
            export.join()
        } finally {
            export.cancel()
        }
    }

    @Test
    fun `HLS duration follows the video rather than padded audio or rounded UI seconds`() {
        unmockkObject(LocalMediaProbe)
        mockkConstructor(MediaExtractor::class)
        val audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 1).apply {
            setLong(MediaFormat.KEY_DURATION, 6_016_000)
        }
        val video = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
            setLong(MediaFormat.KEY_DURATION, 6_000_000)
        }
        every { anyConstructed<MediaExtractor>().setDataSource(any<String>()) } returns Unit
        every { anyConstructed<MediaExtractor>().trackCount } returns 2
        every { anyConstructed<MediaExtractor>().getTrackFormat(0) } returns audio
        every { anyConstructed<MediaExtractor>().getTrackFormat(1) } returns video
        every { anyConstructed<MediaExtractor>().release() } returns Unit
        val context: android.app.Application = RuntimeEnvironment.getApplication()
        assertEquals(6.0, LocalMediaProbe.probeDurationSecondsExact(context, "/download/video.mp4"))
        assertEquals(7L, LocalMediaProbe.probeDurationSeconds(context, "/download/video.mp4"))
    }

    private suspend fun startAndCancel(
        uri: String,
        startMs: Long = 0,
        sourceMimeType: String? = null,
    ): EditedMediaItem = coroutineScope {
        val owner = Any()
        val failure = CompletableDeferred<Throwable?>()
        val export = launch(Dispatchers.Default) {
            failure.complete(runCatching {
                transcoder.transcodeStream(
                    uri, target, streamMuxer, startMs = startMs, sourceMimeType = sourceMimeType, owner = owner,
                )
            }.exceptionOrNull())
        }
        try {
            val item = if (uri.startsWith("http")) {
                assertNotNull(startedCompositions.poll(5, TimeUnit.SECONDS), "export did not start")
                    .sequences.single().editedMediaItems.single()
            } else {
                assertNotNull(startedItems.poll(5, TimeUnit.SECONDS), "export did not start")
            }
            assertTrue(transcoder.cancelOwnedInFlightAndAwait(owner, 5_000), "cancel did not release the export")
            assertIs<CancellationException>(withTimeout(5_000) { failure.await() })
            export.join()
            item
        } finally {
            export.cancel()
        }
    }

    private val streamMuxer = mockk<Muxer.Factory>(relaxed = true)
}
