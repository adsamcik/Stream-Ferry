package com.adsamcik.streamferry.integration

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingAcceptanceTest {
    @Test fun nonemptyErrorBodiesCannotPassProgressiveOrSegmentFetch() {
        Responses().use { server ->
            val acceptance = JellyfinLiveIntegrationTest()
            for (status in listOf(404, 500)) {
                server.enqueue(status, "error".repeat(300))
                assertFailsWith<AssertionError> { acceptance.fetch(server.url("/stream").toString(), null) }
            }
            server.enqueue(200, "full response")
            assertFailsWith<AssertionError> { acceptance.fetch(server.url("/stream").toString(), "bytes=0-2") }
            server.enqueue(206, "abc")
            assertEquals("abc", acceptance.fetch(server.url("/stream").toString(), "bytes=0-2").decodeToString())
        }
    }

    @Test fun hlsMustActuallyReachMediaAndRejectsMissingOrTooDeepPlaylists() {
        val acceptance = JellyfinLiveIntegrationTest()
        assertFailsWith<AssertionError> { acceptance.followHlsAndAssertNoLeak("#EXTM3U\n", "unused-token") }
        Responses().use { server ->
            val next = server.url("/stream?seg=next").toString()
            val playlist = "#EXTM3U\n$next\n"
            repeat(4) { server.enqueue(200, playlist) }
            assertFailsWith<AssertionError> { acceptance.followHlsAndAssertNoLeak(playlist, "unused-token") }
            server.enqueue(404, "missing segment")
            assertFailsWith<AssertionError> { acceptance.followHlsAndAssertNoLeak(playlist, "unused-token") }
            server.enqueue(200, "encoded segment")
            acceptance.followHlsAndAssertNoLeak(playlist, "unused-token")
        }
    }

    /** Real HTTP responses using the host JDK, without another test dependency. */
    private class Responses : AutoCloseable {
        private val replies = LinkedBlockingQueue<Pair<Int, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (code, body) = replies.poll() ?: (500 to "Unexpected request")
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

        fun enqueue(code: Int, body: String) { replies.add(code to body) }
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
        override fun close() { server.stop(0) }
    }
}
