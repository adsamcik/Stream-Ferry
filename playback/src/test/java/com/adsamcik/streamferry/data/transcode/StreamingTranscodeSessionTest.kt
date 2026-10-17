package com.adsamcik.streamferry.data.transcode

import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import com.adsamcik.streamferry.core.transcode.AudioCodec
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingTranscodeSessionTest {
    private val target = TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, StreamContainer.HLS_FMP4, ResolutionTier.HD_720P, 30)

    @Test
    fun `unknown length remote source publishes Cast media before export completion`() = runBlocking {
        proveStreaming(hls = true)
    }

    @Test
    fun `the same continuous export publishes DLNA transport bytes before completion`() = runBlocking {
        proveStreaming(hls = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `nonzero Cast and DLNA restarts allow sequential catchup and start on first media`() = runTest {
        for (hls in listOf(true, false)) {
            val transcoder = mockk<OnDeviceTranscoder>()
            every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
            val completeExport = CompletableDeferred<Unit>()
            coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                assertEquals(120_000L, arg<Long>(3))
                delay(60_000) // A source without a seek map needs longer than the old 45 s deadline.
                val muxer = arg<Muxer.Factory>(2).create("unused")
                val video = muxer.addTrack(videoFormat())
                writeFrames(muxer, video, segments = 2)
                completeExport.await()
                muxer.close()
            }
            val session = StreamingTranscodeSession(
                transcoder, "https://source.test/unseekable.ts", target, mockk<DiagnosticSink>(relaxed = true), hls,
                startPositionSeconds = 120, exportDispatcher = StandardTestDispatcher(testScheduler),
            )
            try {
                val readiness = async { session.awaitReady() }
                runCurrent()
                advanceTimeBy(59_999)
                assertFalse(readiness.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                readiness.await()
                assertFalse(completeExport.isCompleted, "playback must not wait for complete conversion")
                if (hls) {
                    assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(0, false))
                } else {
                    assertNotNull(session.openProgressive()).use { assertEquals(0x47, it.read()) }
                }
            } finally {
                session.release()
                completeExport.complete(Unit)
                runCurrent()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stalled initial and nonzero startup still time out and cancel the export`() = runTest {
        for (startSeconds in listOf(0L, 120L)) {
            val transcoder = mockk<OnDeviceTranscoder>()
            every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
            coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                CompletableDeferred<Unit>().await()
            }
            val session = StreamingTranscodeSession(
                transcoder, "content://source/unseekable", target, mockk<DiagnosticSink>(relaxed = true), hls = true,
                startPositionSeconds = startSeconds, exportDispatcher = StandardTestDispatcher(testScheduler),
            )
            val readiness = async { runCatching { session.awaitReady() }.exceptionOrNull() }
            runCurrent()
            advanceTimeBy(45_000 + startSeconds * 1_000 - 1)
            assertFalse(readiness.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertIs<TimeoutCancellationException>(readiness.await())
            coVerify(exactly = 1) { transcoder.cancelOwnedInFlightAndAwait(any(), any()) }
            assertEquals(null, session.openProgressive(), "timeout must release the session")
        }
    }

    private suspend fun proveStreaming(hls: Boolean) {
        val transcoder = mockk<OnDeviceTranscoder>()
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        val completeExport = CompletableDeferred<Unit>()
        val exportCompleted = CompletableDeferred<Unit>()
        val reconnects = AtomicInteger()
        coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val muxer = arg<Muxer.Factory>(2).create("unused")
            val video = muxer.addTrack(videoFormat())
            writeFrames(muxer, video, segments = 2, startUs = 12_000_000, finalBoundary = false)
            completeExport.await()
            muxer.close()
            exportCompleted.complete(Unit)
        }
        val session = StreamingTranscodeSession(
            transcoder, "https://source.test/live.m3u8", target, mockk<DiagnosticSink>(relaxed = true), hls,
            startPositionSeconds = 12, sourceMimeType = "application/x-mpegURL",
            onReconnectRequired = { reconnects.incrementAndGet() },
        )
        try {
            // Metadata probes do not even reserve an encoder.
            session.playlist("http://phone/session/id", allowExport = false)
            session.initSegment(allowExport = false)
            assertEquals(0, reconnects.get())
            coVerify(exactly = 0) { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            withTimeout(5_000) { session.awaitReady() }
            assertFalse(exportCompleted.isCompleted, "readiness must not await the completed video")
            if (hls) {
                assertIs<ClientTranscodeSource.Resource.Ready>(session.initSegment(false))
                val first = assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(0, true))
                assertTrue(first.bytes.isNotEmpty())
                assertContentEquals(first.bytes, assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(0, true)).bytes)
                val playlist = session.mediaPlaylist("http://phone/session/id")
                assertTrue(playlist.contains("#EXTINF:"))
                assertFalse(playlist.contains("#EXT-X-ENDLIST"))
            } else {
                assertTrue(session.isProgressive)
                val stream = assertNotNull(session.openProgressive())
                val prefix = ByteArray(6_016)
                val count = stream.use { it.read(prefix) }
                assertTrue(count > 188, "one HTTP read should batch the available TS packets")
                assertEquals(0x47, prefix[0].toInt() and 0xff)
                // DLNA receivers commonly close an initial GET probe, then reopen from byte zero.
                assertNotNull(session.openProgressive()).use {
                    assertContentEquals(prefix.copyOf(count), it.readNBytes(count))
                }
            }
            completeExport.complete(Unit)
            withTimeout(5_000) { exportCompleted.await() }
            assertEquals(0, reconnects.get(), "metadata and bounded probe replay must not restart playback")
        } finally {
            completeExport.complete(Unit)
            session.release()
        }
        assertEquals(null, session.openProgressive())
        assertEquals(0, reconnects.get(), "a released session must not request a reconnect")
    }

    @Test
    fun `DLNA reconnect after startup replay expires requests exactly one fresh stream`() = runBlocking {
        val transcoder = mockk<OnDeviceTranscoder>()
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        val keepRunning = CompletableDeferred<Unit>()
        val reconnects = AtomicInteger()
        coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val muxer = arg<Muxer.Factory>(2).create("unused")
            val video = muxer.addTrack(videoFormat())
            // Actual packetized output exceeds the two MiB byte-zero replay budget.
            val keyframe = ByteArray(3 * 1024 * 1024) { 1 }.apply {
                this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1; this[4] = 0x65
            }
            muxer.writeSampleData(video, ByteBuffer.wrap(keyframe), BufferInfo(0, keyframe.size, C.BUFFER_FLAG_KEY_FRAME))
            keepRunning.await()
            muxer.close()
        }
        val session = StreamingTranscodeSession(
            transcoder, "https://source.test/video.mp4", target, mockk<DiagnosticSink>(relaxed = true), hls = false,
            onReconnectRequired = { reconnects.incrementAndGet() },
        )
        try {
            withTimeout(5_000) { session.awaitReady() }
            val stream = assertNotNull(session.openProgressive())
            val consumed = stream.readNBytes(2 * 1024 * 1024 + 6_016)
            assertEquals(2 * 1024 * 1024 + 6_016, consumed.size)
            assertEquals(null, session.openProgressive())
            assertEquals(0, reconnects.get(), "parallel GETs must not replace an active reader")
            stream.close()
            // A retired HTTP reader may be closed twice; that must not affect reconnect ownership.
            stream.close()
            session.playlist("http://phone", allowExport = false)
            session.initSegment(allowExport = false)
            assertEquals(0, reconnects.get(), "HEAD and metadata probes must remain side-effect free")
            val pending = assertNotNull(session.openProgressive())
            assertEquals(1, reconnects.get(), "a closed receiver needs a replacement URL and encoder")
            val retry = assertNotNull(session.openProgressive())
            assertEquals(1, reconnects.get(), "HTTP retries must not repeatedly restart playback")
            retry.close()
            assertEquals(-1, retry.read())
            val readStarted = CompletableDeferred<Unit>()
            val pendingRead = async(Dispatchers.IO) {
                readStarted.complete(Unit)
                pending.read()
            }
            readStarted.await()
            delay(50)
            assertFalse(pendingRead.isCompleted, "reconnect must not fail HTTP or consume media before the TV state is read")
            session.release()
            assertEquals(-1, withTimeout(5_000) { pendingRead.await() })
        } finally {
            session.release()
            keepRunning.complete(Unit)
        }
        assertEquals(null, session.openProgressive())
        assertEquals(1, reconnects.get())
    }

    @Test
    fun `Cast can start above zero and make progress while retaining nearby out of order retries`() = runBlocking {
        val transcoder = mockk<OnDeviceTranscoder>()
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        val tenSamples = CompletableDeferred<Unit>()
        val keepRunning = CompletableDeferred<Unit>()
        coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val muxer = arg<Muxer.Factory>(2).create("unused")
            val video = muxer.addTrack(videoFormat())
            writeFrames(muxer, video, segments = 20) { if (it == 10) tenSamples.complete(Unit) }
            keepRunning.await()
            muxer.close()
        }
        val session = StreamingTranscodeSession(
            transcoder, "content://source/video", target, mockk<DiagnosticSink>(relaxed = true), hls = true,
        )
        try {
            withTimeout(5_000) {
                session.awaitReady()
                while (session.mediaPlaylist("http://phone").lineSequence().count { it.startsWith("#EXTINF") } < 8) delay(5)
            }
            assertFalse(tenSamples.isCompleted, "the encoder must block when its eight-segment buffer is full")
            assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(5, true))
            assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(3, true), "two prior segments remain available for parallel retries")
            withTimeout(5_000) { tenSamples.await() }
            // Never requesting zero through two must not strand the producer when playback reaches
            // the end of its original eight-segment window.
            for (index in 6..15) assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(index, true))
        } finally {
            session.release()
            keepRunning.complete(Unit)
        }
    }

    @Test
    fun `large delivered fragments yield retry space so the next fragment cannot deadlock`() = runBlocking<Unit> {
        val transcoder = mockk<OnDeviceTranscoder>()
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        val thirdPublished = CompletableDeferred<Unit>()
        val keepRunning = CompletableDeferred<Unit>()
        coEvery { transcoder.transcodeStream(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val muxer = arg<Muxer.Factory>(2).create("unused")
            val video = muxer.addTrack(videoFormat())
            val sample = ByteArray(9 * 1024 * 1024) { 1 }.apply {
                this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1; this[4] = 0x65
            }
            writeFrames(muxer, video, segments = 3, keyframe = sample)
            thirdPublished.complete(Unit)
            keepRunning.await()
            muxer.close()
        }
        val session = StreamingTranscodeSession(
            transcoder, "file:///large.mp4", target, mockk<DiagnosticSink>(relaxed = true), hls = true,
        )
        try {
            withTimeout(10_000) {
                session.awaitReady()
                while (session.mediaPlaylist("http://phone").lineSequence().count { it.startsWith("#EXTINF") } < 2) delay(5)
            }
            assertFalse(thirdPublished.isCompleted, "three 9 MiB fragments exceed the 24 MiB buffer")
            assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(0, true))
            withTimeout(10_000) { thirdPublished.await() }
            assertIs<ClientTranscodeSource.Resource.Ready>(session.mediaSegment(2, false))
        } finally {
            session.release()
            keepRunning.complete(Unit)
        }
    }

    @Test
    fun `fragment writer publishes complete boxes while retaining incomplete media`() {
        val init = mutableListOf<ByteArray>()
        val media = mutableListOf<ByteArray>()
        val writer = FragmentOutput(init::add, media::add)
        val header = box("ftyp", byteArrayOf(1)) + box("moov", byteArrayOf(2))
        val fragment = box("moof", byteArrayOf(3)) + box("mdat", byteArrayOf(4, 5, 6))
        for (byte in header + fragment.dropLast(1)) writer.write(byte.toInt())
        assertContentEquals(header, init.single())
        assertTrue(media.isEmpty())
        writer.write(fragment.last().toInt())
        assertContentEquals(fragment, media.single())
        writer.close()
    }

    @Test
    fun `fragment writer fails a truncated stream instead of publishing it`() {
        val writer = FragmentOutput({}, {})
        writer.write(box("moof", byteArrayOf(1)))
        assertFailsWith<IOException> { writer.close() }
    }

    @Test
    fun `seek timestamp rebasing uses one shared origin for audio and video`() {
        val writes = mutableListOf<Pair<Int, Long>>()
        val sink = mockk<Muxer>(relaxed = true)
        every { sink.addTrack(any()) } returnsMany listOf(0, 1)
        every { sink.writeSampleData(any(), any(), any()) } answers {
            writes += firstArg<Int>() to thirdArg<BufferInfo>().presentationTimeUs
        }
        val factory = mockk<Muxer.Factory>()
        every { factory.create(any<String>()) } returns sink
        val muxer = TimestampOriginFactory(factory).create("unused")
        val video = muxer.addTrack(videoFormat())
        val audio = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        muxer.writeSampleData(video, ByteBuffer.wrap(byteArrayOf(1)), BufferInfo(12_020_000, 1, 0))
        assertTrue(writes.isEmpty(), "wait for both tracks before choosing a common origin")
        muxer.writeSampleData(audio, ByteBuffer.wrap(byteArrayOf(1)), BufferInfo(12_000_000, 1, 0))
        muxer.writeSampleData(video, ByteBuffer.wrap(byteArrayOf(1)), BufferInfo(12_060_000, 1, 0))
        muxer.writeSampleData(video, ByteBuffer.allocate(0), BufferInfo(12_100_000, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
        assertEquals(listOf(0 to 20_000L, 1 to 0L, 0 to 60_000L, 0 to 100_000L), writes)
        muxer.close()
    }

    private fun videoFormat() = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(640).setHeight(360).setInitializationData(listOf(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0x95.toByte(), 0xa8.toByte(), 0x28, 0x0f, 0, 0x44, 0x7a),
            byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x38, 0x80.toByte()),
        )).build()

    private fun writeFrames(
        muxer: Muxer,
        video: Int,
        segments: Int,
        startUs: Long = 0,
        keyframe: ByteArray = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3),
        finalBoundary: Boolean = true,
        onBoundary: (Int) -> Unit = {},
    ) {
        val delta = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3)
        val endFrame = segments * 60 - if (finalBoundary) 0 else 1
        for (frame in 0..endFrame) {
            val isKeyframe = frame % 60 == 0
            val sample = if (isKeyframe) keyframe else delta
            muxer.writeSampleData(
                video, ByteBuffer.wrap(sample),
                BufferInfo(startUs + frame * 1_000_000L / 30, sample.size, if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0),
            )
            if (isKeyframe) onBoundary(frame / 60)
        }
    }

    private fun box(type: String, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put(type.toByteArray()).put(bytes).array()
}
