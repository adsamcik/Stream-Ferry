package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.transcode.AudioCodec
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.StreamContainer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparedTranscodeFileSessionTest {
    private val cache = Files.createTempDirectory("prepared-transcode-test").toFile()
    private val transcoder = mockk<OnDeviceTranscoder>()
    private val logger = mockk<DiagnosticSink>(relaxed = true)
    private val target = TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, StreamContainer.MP4, ResolutionTier.HD_720P, 30)

    @After fun cleanup() { cache.deleteRecursively() }

    @Test fun `completed output remains readable until its owner releases it`() = runBlocking {
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        coEvery { transcoder.transcodeFile(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<File>().writeBytes(byteArrayOf(1, 2, 3))
        }
        val session = session()
        val progress = mutableListOf<Int?>()
        val file = session.prepare { progress += it }
        assertEquals(session.file, file)
        assertEquals(listOf<Int?>(100), progress)
        assertEquals(3L, file.length())
        session.release()
        assertFalse(file.exists())
        verify(exactly = 1) { transcoder.cancelOwnedInFlightAndAwait(any(), any()) }
    }

    @Test fun `caller cancellation drains only its export and removes partial output`() = runBlocking {
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns true
        coEvery { transcoder.transcodeFile(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<File>().writeBytes(byteArrayOf(1))
            throw CancellationException("stopped")
        }
        val session = session()
        assertFailsWith<CancellationException> { session.prepare() }
        assertFalse(session.file.exists())
        verify(exactly = 1) { transcoder.cancelOwnedInFlightAndAwait(any(), any()) }
    }

    @Test fun `a still writing native export retains its path rather than deleting it`() = runBlocking {
        every { transcoder.cancelOwnedInFlightAndAwait(any(), any()) } returns false
        coEvery { transcoder.transcodeFile(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<File>().writeBytes(byteArrayOf(1))
            throw CancellationException("native release stalled")
        }
        val session = session()
        assertFailsWith<CancellationException> { session.prepare() }
        assertTrue(session.file.exists())
    }

    private fun session() = PreparedTranscodeFileSession(
        transcoder, "http://127.0.0.1:1234/stream", target, cache, logger,
        sourceMimeType = "application/x-mpegURL",
    )
}
