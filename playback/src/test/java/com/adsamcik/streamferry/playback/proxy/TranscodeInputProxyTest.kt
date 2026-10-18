package com.adsamcik.streamferry.playback.proxy

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.source.api.*
import io.mockk.*
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TranscodeInputProxyTest {
    private fun fetch(address: String, id: String, query: String = "", range: String? = null): String =
        Socket("127.0.0.1", address.substringAfter(':').toInt()).use { socket ->
            socket.soTimeout = 3000
            val headers = range?.let { "Range: $it\r\n" } ?: ""
            socket.getOutputStream().write("GET /session/$id/stream$query HTTP/1.1\r\nHost: localhost\r\n$headers\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString()
        }

    @Test fun `encoder input honors source ranges without producing renderer throughput or rejection evidence`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, object : DiagnosticSink {})
        val input = sessions.create("video/mp4", totalLength = 8)
        val source = mockk<StreamLease>()
        every { source.retainResources(any()) } just Runs
        every { source.descriptor } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8)
        coEvery { source.open(ByteRange(2, 4)) } answers {
            Result.success(StreamResponse(206, mapOf("Content-Length" to "3", "Content-Range" to "bytes 2-4/8"), ByteArrayInputStream("cde".toByteArray())))
        }
        val bytes = AtomicLong()
        val deliveries = AtomicLong()
        val rendererConnections = AtomicLong()
        val rendererDone = CountDownLatch(1)
        proxy.byteListener = { bytes.addAndGet(it) }
        proxy.mediaDeliveryListener = { deliveries.incrementAndGet() }
        proxy.onDownstreamClosed = { _, _, _ -> rendererConnections.incrementAndGet(); rendererDone.countDown() }
        proxy.registerStreamLease(input.id, source, rendererOutput = false)
        try {
            val address = proxy.start("127.0.0.1")
            val response = fetch(address, input.id, range = "bytes=2-4")
            assertTrue(response.startsWith("HTTP/1.1 206"))
            assertTrue(response.endsWith("cde"))
            assertEquals(0L, bytes.get())
            assertEquals(0L, deliveries.get())
            assertEquals(0L, rendererConnections.get())
            val output = sessions.create("video/mp4", totalLength = 8)
            proxy.registerStreamLease(output.id, source)
            assertTrue(fetch(address, output.id, range = "bytes=2-4").endsWith("cde"))
            assertTrue(rendererDone.await(2, TimeUnit.SECONDS))
            assertEquals(3L, bytes.get())
            assertEquals(1L, rendererConnections.get())
            coVerify(exactly = 2) { source.open(ByteRange(2, 4)) }
        } finally { proxy.stop() }
    }

    @Test fun `encoder HLS input resolves and rewrites source resources without renderer evidence`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, object : DiagnosticSink {})
        val input = sessions.create("application/vnd.apple.mpegurl", isHls = true)
        val source = mockk<StreamLease>()
        every { source.retainResources(any()) } just Runs
        val segment = StreamResourceRef("opaque-resource", StreamResourceKind.MEDIA)
        val sourceReference = "https://private.example/segment.ts?token=secret"
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n$sourceReference\n#EXT-X-ENDLIST\n"
        coEvery { source.open(range = null) } answers {
            Result.success(StreamResponse(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), ByteArrayInputStream(playlist.toByteArray())))
        }
        coEvery { source.resolve(sourceReference, null) } returns Result.success(segment)
        coEvery { source.open(segment, null) } answers {
            Result.success(StreamResponse(200, mapOf("Content-Type" to "video/mp2t", "Content-Length" to "8"), ByteArrayInputStream("segment!".toByteArray())))
        }
        val bytes = AtomicLong()
        val deliveries = AtomicLong()
        val connections = AtomicLong()
        proxy.byteListener = { bytes.addAndGet(it) }
        proxy.mediaDeliveryListener = { deliveries.incrementAndGet() }
        proxy.onDownstreamClosed = { _, _, _ -> connections.incrementAndGet() }
        proxy.registerStreamLease(input.id, source, rendererOutput = false)
        try {
            val address = proxy.start("127.0.0.1")
            val rewritten = fetch(address, input.id)
            assertFalse(rewritten.contains("private.example"))
            assertFalse(rewritten.contains("secret"))
            val opaque = assertNotNull(Regex("seg=([A-Za-z0-9_-]+)").find(rewritten)).groupValues[1]
            assertTrue(fetch(address, input.id, "?seg=$opaque").endsWith("segment!"))
            assertEquals(0L, bytes.get())
            assertEquals(0L, deliveries.get())
            assertEquals(0L, connections.get())
            coVerify(exactly = 1) { source.resolve(sourceReference, null) }
            coVerify(exactly = 1) { source.open(segment, null) }
        } finally { proxy.stop() }
    }
}
