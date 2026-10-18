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
        coEvery { lease.open(range = any()) } returns Result.success(StreamResponse(200, mapOf("Content-Length" to "8", "ETag" to "\"same\""), body))
    }

    private fun request(address: String, id: String, query: String = "", method: String = "GET", range: String? = null): Socket = Socket("127.0.0.1", address.substringAfter(':').toInt()).apply {
        soTimeout = 3000
        val rangeHeader = range?.let { "Range: $it\r\n" }.orEmpty()
        getOutputStream().write("$method /session/$id/stream$query HTTP/1.1\r\nHost: localhost\r\n$rangeHeader\r\n".toByteArray())
        getOutputStream().flush()
    }

    @Test fun `progressive media rejects encoded bodies before success headers with and without known length`() {
        for (totalLength in listOf(8L, null)) {
            for (range in if (totalLength == null) listOf(null) else listOf(null, "bytes=2-5")) {
                for (encoding in listOf(null, "identity", "gzip")) {
                    val sessions = SessionRegistry()
                    val proxy = LocalProxyServer(sessions, Log())
                    val session = sessions.create("video/mp4", totalLength = totalLength)
                    val source = lease(ByteArrayInputStream(byteArrayOf()))
                    every { source.descriptor } returns StreamDescriptor(
                        "video/mp4", "mp4", isTranscoding = false, isByteSeekable = totalLength != null,
                        totalLength = totalLength,
                    )
                    val closed = CountDownLatch(1)
                    val reads = java.util.concurrent.atomic.AtomicInteger()
                    val bytes = if (range == null) "allmedia".toByteArray() else "lmed".toByteArray()
                    coEvery { source.open(range = any()) } answers { Result.success(StreamResponse(
                        if (range == null) 200 else 206,
                        mapOf("Content-Length" to bytes.size.toString()) +
                            range?.let { mapOf("Content-Range" to "bytes 2-5/8") }.orEmpty() +
                            encoding?.let { mapOf("Content-Encoding" to it) }.orEmpty(),
                        object : ByteArrayInputStream(bytes) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                reads.incrementAndGet()
                                return super.read(buffer, offset, length)
                            }
                            override fun close() { closed.countDown(); super.close() }
                        },
                    )) }
                    proxy.registerStreamLease(session.id, source)
                    try {
                        val result = request(proxy.start("127.0.0.1"), session.id, range = range).use {
                            it.getInputStream().readBytes().decodeToString()
                        }
                        assertTrue(closed.await(2, TimeUnit.SECONDS))
                        if (encoding == "gzip") {
                            assertTrue(result.startsWith("HTTP/1.1 502"))
                            assertEquals("", result.substringAfter("\r\n\r\n"))
                            assertEquals(0, reads.get(), "Encoded bytes must never reach the media decoder")
                        } else {
                            assertTrue(result.startsWith(if (range == null) "HTTP/1.1 200" else "HTTP/1.1 206"))
                            assertEquals(bytes.decodeToString(), result.substringAfter("\r\n\r\n"))
                        }
                        coVerify(exactly = 1) { source.open(range = any()) }
                    } finally { proxy.stop() }
                }
            }
        }
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
            Result.success(StreamResponse(206, mapOf("Content-Length" to "4", "Content-Range" to "bytes 4-7/8", "ETag" to "\"same\"", "Content-Encoding" to "identity"), ByteArrayInputStream("rest".toByteArray())))
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

    @Test fun `progressive resume never splices a changed or unproven representation`() {
        val stable = mapOf("ETag" to "\"same\"")
        val modified = mapOf("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Date" to "Wed, 21 Oct 2015 07:30:00 GMT")
        val cases = listOf(
            stable to mapOf("ETag" to "\"changed\""),
            stable to emptyMap(),
            stable to (stable + ("Content-Encoding" to "gzip")),
            emptyMap<String, String>() to emptyMap(),
            mapOf("ETag" to "W/\"same\"") to mapOf("ETag" to "W/\"same\""),
            modified to (modified + ("Last-Modified" to "Wed, 21 Oct 2015 07:29:00 GMT")),
        )
        for ((initial, replacement) in cases) {
            val sessions = SessionRegistry()
            val log = Log()
            val proxy = LocalProxyServer(sessions, log)
            val session = sessions.create("video/mp4", totalLength = 8)
            val source = lease(ByteArrayInputStream(byteArrayOf()))
            coEvery { source.open(range = any()) } answers { Result.success(StreamResponse(
                200, mapOf("Content-Length" to "8") + initial, ByteArrayInputStream("part".toByteArray()),
            )) }
            coEvery { source.open(range = match { it.start == 4L && it.endInclusive == 7L }) } answers {
                Result.success(StreamResponse(206,
                    mapOf("Content-Length" to "4", "Content-Range" to "bytes 4-7/8") + replacement,
                    ByteArrayInputStream("WRNG".toByteArray()),
                ))
            }
            val callbacks = CopyOnWriteArrayList<Boolean>()
            proxy.onDownstreamClosed = { _, _, clean -> callbacks += clean }
            proxy.registerStreamLease(session.id, source)
            try {
                val body = request(proxy.start("127.0.0.1"), session.id).use {
                    it.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
                }
                assertEquals("part", body, "$initial -> $replacement")
                assertTrue(log.finished.await(2, TimeUnit.SECONDS))
                assertTrue(log.outcomes.single().contains("clean=false outcome=upstream_failed"))
                assertTrue(callbacks.isEmpty(), "An upstream identity failure must not become a codec rejection")
            } finally { proxy.stop() }
        }
    }

    @Test fun `progressive resume accepts a stable strong last modified validator`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, Log())
        val session = sessions.create("video/mp4", totalLength = 8)
        val validator = mapOf("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Date" to "Wed, 21 Oct 2015 07:30:00 GMT")
        val source = lease(ByteArrayInputStream(byteArrayOf()))
        coEvery { source.open(range = any()) } answers { Result.success(StreamResponse(
            200, mapOf("Content-Length" to "8") + validator, ByteArrayInputStream("part".toByteArray()),
        )) }
        coEvery { source.open(range = match { it.start == 4L && it.endInclusive == 7L }) } answers {
            Result.success(StreamResponse(206,
                mapOf("Content-Length" to "4", "Content-Range" to "bytes 4-7/8") + validator,
                ByteArrayInputStream("rest".toByteArray()),
            ))
        }
        proxy.registerStreamLease(session.id, source)
        try {
            val result = request(proxy.start("127.0.0.1"), session.id).use { it.getInputStream().readBytes().decodeToString() }
            assertEquals("partrest", result.substringAfter("\r\n\r\n"))
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
        every { source.retainResources(any()) } just Runs
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
        every { source.retainResources(any()) } just Runs
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

    @Test fun `Stop closes a body registered while the previous response is removed and closed`() {
        val sessions = SessionRegistry()
        val session = sessions.create("video/mp4", totalLength = 8)
        val proxy = LocalProxyServer(sessions, Log())
        val cleanupObservedEmpty = CountDownLatch(1)
        val registrationFinished = CountDownLatch(1)
        val oldBodyClosing = CountDownLatch(1)
        val allowOldBodyClose = CountDownLatch(1)
        val replacementClosed = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val old = StreamResponse(200, emptyMap(), object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                oldBodyClosing.countDown()
                check(allowOldBodyClose.await(5, TimeUnit.SECONDS))
                super.close()
            }
        })
        val replacement = StreamResponse(200, emptyMap(), object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { replacementClosed.countDown(); super.close() }
        })
        val track = proxy.javaClass.getDeclaredMethod("trackResponse", String::class.java, StreamResponse::class.java)
            .apply { isAccessible = true }
        val close = proxy.javaClass.getDeclaredMethod("closeUpstream", String::class.java, StreamResponse::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val responses = proxy.javaClass.getDeclaredField("activeResponses").apply { isAccessible = true }
            .get(proxy) as MutableMap<String, MutableSet<StreamResponse>>
        track.invoke(proxy, session.id, old)
        val backing = responses.getValue(session.id)
        val registration = Thread {
            try {
                check(cleanupObservedEmpty.await(2, TimeUnit.SECONDS))
                track.invoke(proxy, session.id, replacement)
            } catch (error: Throwable) { failures += error }
            finally { registrationFinished.countDown() }
        }.apply { isDaemon = true }
        responses[session.id] = object : MutableSet<StreamResponse> by backing {
            override fun isEmpty(): Boolean {
                val observedEmpty = backing.isEmpty()
                if (observedEmpty) {
                    cleanupObservedEmpty.countDown()
                    // Let the real competing registration either complete (the broken case), or
                    // reach the held lifecycle monitor. Avoid a timing-dependent race/sleep.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (registrationFinished.count != 0L && registration.state != Thread.State.BLOCKED &&
                        System.nanoTime() < deadline) Thread.yield()
                    check(registrationFinished.count == 0L || registration.state == Thread.State.BLOCKED)
                }
                return observedEmpty
            }
        }
        val cleanup = Thread {
            try { close.invoke(proxy, session.id, old) }
            catch (error: Throwable) { failures += error }
        }.apply { isDaemon = true }
        try {
            registration.start()
            cleanup.start()
            assertTrue(oldBodyClosing.await(2, TimeUnit.SECONDS))
            assertTrue(registrationFinished.await(2, TimeUnit.SECONDS),
                "A blocking provider close must not hold the registration/Stop ownership lock")
            assertTrue(failures.isEmpty(), failures.joinToString())
            proxy.stop()
            assertTrue(replacementClosed.await(1, TimeUnit.SECONDS),
                "Stop must retain ownership of the new upstream body during response cleanup")
        } finally {
            allowOldBodyClose.countDown()
            cleanup.join(2000)
            registration.join(2000)
            proxy.stop()
            replacement.close()
        }
    }
}
