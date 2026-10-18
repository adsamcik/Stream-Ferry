package com.adsamcik.streamferry.playback.proxy

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.source.api.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HlsBodyRecoveryTest {
    private class Source : StreamLease {
        override val descriptor = StreamDescriptor("application/vnd.apple.mpegurl", "hls", isTranscoding = false, isByteSeekable = false, totalLength = null)
        @Volatile var playlist = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
        @Volatile var body: suspend (StreamResourceRef, ByteRange?) -> StreamResponse = { _, _ -> response("allmedia") }
        val ranges = CopyOnWriteArrayList<ByteRange?>()
        val retained = CopyOnWriteArrayList<Set<StreamResourceRef>>()
        override suspend fun open(range: ByteRange?) = Result.success(StreamResponse(
            200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), ByteArrayInputStream(playlist.toByteArray()),
        ))
        override suspend fun resolve(playlistReference: String, fromPlaylist: StreamResourceRef?) = Result.success(
            StreamResourceRef(playlistReference, if (playlistReference.endsWith(".m3u8")) StreamResourceKind.PLAYLIST else StreamResourceKind.MEDIA),
        )
        override suspend fun open(resource: StreamResourceRef, range: ByteRange?): Result<StreamResponse> {
            ranges += range
            return Result.success(body(resource, range))
        }
        override fun retainResources(resources: Set<StreamResourceRef>) { retained += resources }
    }

    private class Fixture : AutoCloseable {
        val source = Source()
        val sessions = SessionRegistry()
        val session = sessions.create("application/vnd.apple.mpegurl", isHls = true)
        val outcomes = CopyOnWriteArrayList<String>()
        val proxy = LocalProxyServer(sessions, object : DiagnosticSink {
            override fun trace(tag: String, message: String) {
                if (message.startsWith("TV connection closed:")) outcomes += message
            }
        })
        val address: String
        init {
            proxy.registerStreamLease(session.id, source)
            address = proxy.start("127.0.0.1")
        }
        fun socket(query: String = "", range: String? = null): Socket = Socket("127.0.0.1", address.substringAfter(':').toInt()).apply {
            soTimeout = 5000
            val rangeHeader = range?.let { "Range: $it\r\n" }.orEmpty()
            getOutputStream().write("GET /session/${session.id}/stream$query HTTP/1.1\r\nHost: localhost\r\n$rangeHeader\r\n".toByteArray())
            getOutputStream().flush()
        }
        fun fetch(query: String = "", range: String? = null): String = socket(query, range).use { it.getInputStream().readBytes().decodeToString() }
        fun segment(): String = token(fetch())
        override fun close() { proxy.stop() }
    }

    @Test fun `truncated segment key and subtitle bodies resume with stable strong validators`() {
        val etag = mapOf("ETag" to "\"same\"")
        val modified = mapOf("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Date" to "Wed, 21 Oct 2015 07:30:00 GMT")
        for (validator in listOf(etag, modified)) {
            for (name in listOf("segment.ts", "key.bin", "subtitle.vtt")) {
                Fixture().use { fixture ->
                    fixture.source.playlist = "#EXTM3U\n#EXTINF:10,\n$name\n#EXT-X-ENDLIST\n"
                    fixture.source.body = { _, range ->
                        if (range == null) response("part", length = 8, extra = validator)
                        else {
                            assertEquals(ByteRange(4, 7), range)
                            response("rest", range = "bytes 4-7/8", extra = validator)
                        }
                    }
                    val result = fixture.fetch("?seg=${fixture.segment()}")
                    assertEquals("partrest", result.substringAfter("\r\n\r\n"))
                    assertEquals(listOf(null, ByteRange(4, 7)), fixture.source.ranges.toList())
                }
            }
        }
    }

    @Test fun `interrupted requested range resumes at absolute entity offset without widening client window`() {
        Fixture().use { fixture ->
            fixture.source.body = { _, range ->
                when (range) {
                    ByteRange(2, 7) -> StreamResponse(206, mapOf("Content-Length" to "6", "Content-Range" to "bytes 2-7/10", "ETag" to "\"same\""), object : InputStream() {
                        private var next = 0
                        override fun read(): Int = if (next < 2) "cd"[next++].code else throw IOException("connection reset")
                    })
                    ByteRange(4, 7) -> response("efgh", range = "bytes 4-7/10", extra = mapOf("ETag" to "\"same\""))
                    else -> error("Unexpected range $range")
                }
            }
            val result = fixture.fetch("?seg=${fixture.segment()}", "bytes=2-7")
            assertTrue(result.startsWith("HTTP/1.1 206"))
            assertTrue(result.contains("Content-Range: bytes 2-7/10"))
            assertEquals("cdefgh", result.substringAfter("\r\n\r\n"))
            assertEquals(listOf(ByteRange(2, 7), ByteRange(4, 7)), fixture.source.ranges.toList())
        }
    }

    @Test fun `changed identity ignored range and invalid range never append replacement bytes`() {
        val replacements = listOf(
            response("WRONG", range = "bytes 4-7/8", length = 4, extra = mapOf("ETag" to "\"changed\"")),
            response("WRONG", length = 8, extra = mapOf("ETag" to "\"same\"")),
            response("WRONG", range = "bytes 0-3/8", length = 4, extra = mapOf("ETag" to "\"same\"")),
            response("WRONG", range = "bytes 4-7/9", length = 4, extra = mapOf("ETag" to "\"same\"")),
        )
        for (replacement in replacements) {
            Fixture().use { fixture ->
                fixture.source.body = { _, range ->
                    if (range == null) response("part", length = 8, extra = mapOf("ETag" to "\"same\"")) else replacement
                }
                val result = fixture.fetch("?seg=${fixture.segment()}")
                assertEquals("part", result.substringAfter("\r\n\r\n"))
                assertEquals(2, fixture.source.ranges.size)
                assertTrue(fixture.outcomes.last().contains("outcome=upstream_failed"))
            }
        }
    }

    @Test fun `unvalidated bodies fail honestly and repeated interrupted bodies have a fixed retry cap`() {
        for (headers in listOf(emptyMap(), mapOf("ETag" to "W/\"weak\""), mapOf("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Date" to "Wed, 21 Oct 2015 07:28:30 GMT"))) {
            Fixture().use { fixture ->
                fixture.source.body = { _, _ -> response("part", length = 8, extra = headers) }
                assertEquals("part", fixture.fetch("?seg=${fixture.segment()}").substringAfter("\r\n\r\n"))
                assertEquals(1, fixture.source.ranges.size)
            }
        }
        Fixture().use { fixture ->
            fixture.source.body = { _, range ->
                val offset = range?.start ?: 0
                response(('a'.code + offset.toInt()).toChar().toString(), length = 8 - offset,
                    range = range?.let { "bytes ${it.start}-${it.endInclusive}/8" }, extra = mapOf("ETag" to "\"same\""))
            }
            assertEquals("abc", fixture.fetch("?seg=${fixture.segment()}").substringAfter("\r\n\r\n"))
            assertEquals(3, fixture.source.ranges.size)
        }
    }

    @Test fun `first bytes stream immediately and stop aborts blocked body without a resume`() {
        Fixture().use { fixture ->
            val reading = CountDownLatch(1)
            val closed = CountDownLatch(1)
            fixture.source.body = { _, _ -> StreamResponse(200, mapOf("Content-Length" to "8", "ETag" to "\"same\""), object : InputStream() {
                private var first = true
                override fun read(): Int = error("bulk read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) {
                        first = false
                        "part".toByteArray().copyInto(buffer, offset)
                        return 4
                    }
                    reading.countDown()
                    check(closed.await(3, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun close() { closed.countDown() }
            }) }
            fixture.socket("?seg=${fixture.segment()}").use { socket ->
                val input = socket.getInputStream()
                var prefix = ""
                while (!prefix.endsWith("\r\n\r\npart")) prefix += input.read().also { assertTrue(it >= 0) }.toChar()
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                fixture.proxy.stop()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertEquals(1, fixture.source.ranges.size)
                assertEquals(-1, input.read())
            }
        }
    }

    @Test fun `revocation during retry open closes the late response and appends no bytes`() {
        Fixture().use { fixture ->
            val replacementClosed = CountDownLatch(1)
            fixture.source.body = { _, range ->
                if (range == null) response("part", length = 8, extra = mapOf("ETag" to "\"same\""))
                else {
                    fixture.sessions.revokeAll()
                    StreamResponse(206, mapOf("Content-Length" to "4", "Content-Range" to "bytes 4-7/8", "ETag" to "\"same\""), object : ByteArrayInputStream("rest".toByteArray()) {
                        override fun close() { replacementClosed.countDown(); super.close() }
                    })
                }
            }
            assertEquals("part", fixture.fetch("?seg=${fixture.segment()}").substringAfter("\r\n\r\n"))
            assertTrue(replacementClosed.await(2, TimeUnit.SECONDS))
            assertEquals(2, fixture.source.ranges.size)
            assertTrue(fixture.outcomes.last().contains("outcome=cancelled"))
        }
    }

    @Test fun `in flight resource stays retained across playlist windows until its validated body resume finishes`() {
        Fixture().use { fixture ->
            val blocked = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fixture.source.body = { _, range ->
                if (range != null) response("rest", range = "bytes 4-7/8", extra = mapOf("ETag" to "\"same\""))
                else StreamResponse(200, mapOf("Content-Length" to "8", "ETag" to "\"same\""), object : InputStream() {
                    private var first = true
                    override fun read(): Int = error("bulk read expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (first) {
                            first = false
                            "part".toByteArray().copyInto(buffer, offset)
                            return 4
                        }
                        blocked.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                        return -1
                    }
                    override fun close() { resume.countDown() }
                })
            }
            fixture.socket("?seg=${fixture.segment()}").use { socket ->
                assertTrue(blocked.await(2, TimeUnit.SECONDS))
                repeat(5) { generation ->
                    fixture.source.playlist = buildString {
                        append("#EXTM3U\n")
                        repeat(2000) { append("#EXTINF:10,\nsegment-$generation-$it.ts\n") }
                    }
                    assertTrue(fixture.fetch().startsWith("HTTP/1.1 200"))
                }
                assertEquals(8192, fixture.source.retained.last().size)
                assertTrue(fixture.source.retained.last().any { it.opaqueId == "segment.ts" })
                resume.countDown()
                assertEquals("partrest", socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n"))
                assertEquals(listOf(null, ByteRange(4, 7)), fixture.source.ranges.toList())
            }
        }
    }

    @Test fun `playlist refreshes keep stable URLs bounded metadata and sibling rendition references`() {
        Fixture().use { fixture ->
            fixture.source.playlist = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO=\"a\"\nvideo.m3u8\n"
            val master = fixture.fetch()
            val urls = Regex("seg=([A-Za-z0-9_-]+)").findAll(master).map { it.groupValues[1] }.toList()
            assertEquals(master, fixture.fetch())
            val generation = AtomicInteger()
            fixture.source.body = { resource, _ ->
                if (resource.kind == StreamResourceKind.PLAYLIST) {
                    val text = if (resource.opaqueId == "audio.m3u8") "#EXTM3U\n#EXTINF:10,\naudio.ts\n" else buildString {
                        append("#EXTM3U\n")
                        repeat(2000) { append("#EXTINF:10,\nvideo-${generation.get()}-$it.ts\n") }
                    }
                    StreamResponse(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), ByteArrayInputStream(text.toByteArray()))
                } else response("allmedia")
            }
            val audio = fixture.fetch("?seg=${urls[0]}")
            val audioSegment = token(audio)
            repeat(5) {
                generation.set(it)
                assertTrue(fixture.fetch("?seg=${urls[1]}").startsWith("HTTP/1.1 200"))
            }
            assertEquals(8192, fixture.source.retained.last().size)
            assertTrue(fixture.fetch("?seg=$audioSegment").endsWith("allmedia"))
            assertTrue(fixture.source.retained.last().any { it.opaqueId == "audio.ts" })
            val retained = fixture.source.retained.last()
            fixture.fetch("?seg=${urls[1]}")
            assertEquals(retained, fixture.source.retained.last(), "Unchanged refresh must not accumulate provider metadata")
        }
    }

    companion object {
        private fun token(response: String): String = assertNotNull(Regex("seg=([A-Za-z0-9_-]+)").find(response)).groupValues[1]
        private fun response(bytes: String, length: Long = bytes.length.toLong(), range: String? = null, extra: Map<String, String> = emptyMap()): StreamResponse = StreamResponse(
            if (range == null) 200 else 206,
            mapOf("Content-Type" to "video/mp2t", "Content-Length" to length.toString()) + range?.let { mapOf("Content-Range" to it) }.orEmpty() + extra,
            ByteArrayInputStream(bytes.toByteArray()),
        )
    }
}
