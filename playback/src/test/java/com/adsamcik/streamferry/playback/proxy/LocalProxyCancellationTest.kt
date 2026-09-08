package com.adsamcik.streamferry.playback.proxy

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.proxy.MediaDelivery
import com.adsamcik.streamferry.source.api.*
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LocalProxyCancellationTest {
    private class Log : DiagnosticSink {
        val outcomes = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        override fun trace(tag: String, message: String) {
            if (message.startsWith("TV connection closed:")) {
                outcomes += message
                finished.countDown()
            }
        }
    }

    private class BlockedBody : InputStream() {
        val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun read(): Int {
            reading.countDown()
            check(closed.await(5, TimeUnit.SECONDS))
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    private fun lease(body: InputStream): StreamLease = mockk<StreamLease>().also { lease ->
        every { lease.descriptor } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = false, isByteSeekable = true, totalLength = 8)
        coEvery { lease.open(range = any()) } returns Result.success(StreamResponse(200, mapOf("Content-Length" to "8"), body))
    }

    private fun request(address: String, id: String, query: String = "", method: String = "GET"): Socket = Socket("127.0.0.1", address.substringAfter(':').toInt()).apply {
        soTimeout = 3000
        getOutputStream().write("$method /session/$id/stream$query HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        getOutputStream().flush()
    }

    @Test fun `stop during blocked read cancels old transfer before replacement can retry`() {
        val sessions = SessionRegistry()
        val log = Log()
        val proxy = LocalProxyServer(sessions, log)
        val body = BlockedBody()
        val oldLease = lease(body)
        val old = sessions.create("video/mp4", totalLength = 8)
        proxy.registerStreamLease(old.id, oldLease)
        val socket = request(proxy.start("127.0.0.1"), old.id)
        try {
            assertTrue(body.reading.await(2, TimeUnit.SECONDS))
            proxy.stop()
            val replacement = sessions.create("video/mp4", totalLength = 8)
            proxy.registerStreamLease(replacement.id, lease(ByteArrayInputStream("newmedia".toByteArray())))
            val callbacks = CopyOnWriteArrayList<Boolean>()
            proxy.onDownstreamClosed = { _, _, clean -> callbacks += clean }
            val address = proxy.start("127.0.0.1")
            assertTrue(log.finished.await(2, TimeUnit.SECONDS), "Revoked transfer must finish without spending retry budget")
            assertTrue(log.outcomes.first().contains("outcome=cancelled"))
            assertTrue(callbacks.isEmpty(), "Old transfer must not notify the replacement session")
            coVerify(exactly = 1) { oldLease.open(range = any()) }
            request(address, replacement.id).use { next ->
                assertTrue(next.getInputStream().readBytes().decodeToString().endsWith("newmedia"))
            }
        } finally { socket.close(); proxy.stop() }
    }

    @Test fun `invalid resume is incomplete upstream failure and never a codec rejection`() {
        val sessions = SessionRegistry()
        val log = Log()
        val proxy = LocalProxyServer(sessions, log)
        val session = sessions.create("video/mp4", totalLength = 8)
        val source = lease(ByteArrayInputStream("part".toByteArray()))
        proxy.registerStreamLease(session.id, source)
        val callbacks = CopyOnWriteArrayList<Boolean>()
        proxy.onDownstreamClosed = { _, _, clean -> callbacks += clean }
        try {
            request(proxy.start("127.0.0.1"), session.id).use { it.getInputStream().readBytes() }
            assertTrue(log.finished.await(2, TimeUnit.SECONDS))
            assertTrue(log.outcomes.single().contains("clean=false outcome=upstream_failed"))
            assertTrue(callbacks.isEmpty())
            coVerify(exactly = 2) { source.open(range = any()) }
        } finally { proxy.stop() }
    }

    @Test fun `verified range resume delivers exact remaining bytes and completes normally`() {
        val sessions = SessionRegistry()
        val log = Log()
        val proxy = LocalProxyServer(sessions, log)
        val session = sessions.create("video/mp4", totalLength = 8)
        val source = lease(ByteArrayInputStream("part".toByteArray()))
        coEvery { source.open(range = match { it.start == 4L && it.endInclusive == 7L }) } returns
            Result.success(StreamResponse(206, mapOf("Content-Length" to "4", "Content-Range" to "bytes 4-7/8"), ByteArrayInputStream("rest".toByteArray())))
        proxy.registerStreamLease(session.id, source)
        try {
            request(proxy.start("127.0.0.1"), session.id).use {
                assertTrue(it.getInputStream().readBytes().decodeToString().endsWith("partrest"))
            }
            assertTrue(log.finished.await(2, TimeUnit.SECONDS))
            assertTrue(log.outcomes.single().contains("clean=true outcome=complete"))
        } finally { proxy.stop() }
    }

    @Test fun `progressive response with newly declared length cannot claim truncated body completed`() {
        val sessions = SessionRegistry()
        val log = Log()
        val proxy = LocalProxyServer(sessions, log)
        val session = sessions.create("video/mp4")
        val source = lease(ByteArrayInputStream("part".toByteArray()))
        every { source.descriptor } returns StreamDescriptor("video/mp4", "mp4", isTranscoding = true, isByteSeekable = false, totalLength = null)
        proxy.registerStreamLease(session.id, source)
        try {
            request(proxy.start("127.0.0.1"), session.id).use { it.getInputStream().readBytes() }
            assertTrue(log.finished.await(2, TimeUnit.SECONDS))
            assertTrue(log.outcomes.single().contains("clean=false outcome=upstream_failed"))
            coVerify(exactly = 1) { source.open(range = any()) }
        } finally { proxy.stop() }
    }

    @Test fun `truncated HLS resource is upstream failure rather than clean completion`() {
        val sessions = SessionRegistry()
        val log = Log()
        val proxy = LocalProxyServer(sessions, log)
        val deliveries = CopyOnWriteArrayList<MediaDelivery>()
        proxy.mediaDeliveryListener = { deliveries += it }
        val session = sessions.create("application/vnd.apple.mpegurl", isHls = true)
        val source = mockk<StreamLease>()
        val segment = StreamResourceRef("segment", StreamResourceKind.MEDIA)
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
        coEvery { source.open(range = any()) } returns Result.success(StreamResponse(
            200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), ByteArrayInputStream(playlist.toByteArray()),
        ))
        coEvery { source.resolve(any(), any()) } returns Result.success(segment)
        coEvery { source.open(segment, any()) } returns Result.success(StreamResponse(
            200, mapOf("Content-Type" to "video/mp2t", "Content-Length" to "8"), ByteArrayInputStream("part".toByteArray()),
        ))
        proxy.registerStreamLease(session.id, source)
        try {
            val address = proxy.start("127.0.0.1")
            val root = request(address, session.id).use { it.getInputStream().readBytes().decodeToString() }
            val opaque = assertNotNull(Regex("seg=([A-Za-z0-9_-]+)").find(root)).groupValues[1]
            request(address, session.id, "?seg=$opaque").use { it.getInputStream().readBytes() }
            assertTrue(log.outcomes.last().contains("clean=false outcome=upstream_failed"))
            assertTrue(deliveries.isEmpty())
        } finally { proxy.stop() }
    }

    @Test fun `HLS timeline reaches delivery callback only after a complete GET body`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, Log())
        val session = sessions.create("application/vnd.apple.mpegurl", isHls = true)
        val source = mockk<StreamLease>()
        val segment = StreamResourceRef("segment", StreamResourceKind.MEDIA)
        val playlist = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
        coEvery { source.open(range = any()) } answers { Result.success(StreamResponse(
            200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), ByteArrayInputStream(playlist.toByteArray()),
        )) }
        coEvery { source.resolve(any(), any()) } returns Result.success(segment)
        coEvery { source.open(segment, any()) } answers { Result.success(StreamResponse(
            200, mapOf("Content-Type" to "video/mp2t", "Content-Length" to "8"), ByteArrayInputStream("allmedia".toByteArray()),
        )) }
        proxy.registerStreamLease(session.id, source)
        val epoch = proxy.renewDeliveryEpoch(session.id)
        val deliveries = CopyOnWriteArrayList<MediaDelivery>()
        proxy.mediaDeliveryListener = { deliveries += it }
        try {
            val address = proxy.start("127.0.0.1")
            val root = request(address, session.id).use { it.getInputStream().readBytes().decodeToString() }
            assertTrue(deliveries.isEmpty())
            val opaque = assertNotNull(Regex("seg=([A-Za-z0-9_-]+)").find(root)).groupValues[1]
            request(address, session.id, "?seg=$opaque", "HEAD").use { it.getInputStream().readBytes() }
            assertTrue(deliveries.isEmpty())
            request(address, session.id, "?seg=$opaque").use {
                assertTrue(it.getInputStream().readBytes().decodeToString().endsWith("allmedia"))
            }
            assertEquals(listOf(MediaDelivery(session.id, epoch, 0, 10_000)), deliveries)
        } finally { proxy.stop() }
    }
}
