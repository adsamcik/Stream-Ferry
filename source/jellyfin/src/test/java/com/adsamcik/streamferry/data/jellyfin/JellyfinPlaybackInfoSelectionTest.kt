package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.source.api.DiagnosticSink
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JellyfinPlaybackInfoSelectionTest {

    @Test
    fun skipsAnUnplayableFirstMediaSource() = runBlocking {
        val client = client(
            """
            {
              "MediaSources": [
                { "Id": "offline", "Container": "mkv" },
                {
                  "Id": "playable",
                  "Container": "mp4",
                  "SupportsDirectPlay": true,
                  "DirectStreamUrl": "/Videos/playable/stream.mp4",
                  "Size": 2048
                }
              ]
            }
            """.trimIndent(),
        )

        val result = client.playbackInfo(requireTranscode = false)

        assertEquals("playable", result.mediaSourceId)
        assertEquals(false, result.isHls)
        assertEquals(2048, result.totalLength)
    }

    @Test
    fun prefersALaterDirectSourceOverAnEarlierTranscode() = runBlocking {
        val client = client(
            """
            {
              "MediaSources": [
                {
                  "Id": "transcode-only",
                  "Container": "mkv",
                  "SupportsTranscoding": true,
                  "TranscodingUrl": "/Videos/transcode-only/stream.mp4",
                  "TranscodingContainer": "mp4"
                },
                {
                  "Id": "direct",
                  "Container": "mp4",
                  "SupportsDirectStream": true,
                  "DirectStreamUrl": "/Videos/direct/stream.mp4",
                  "Size": 4096
                }
              ]
            }
            """.trimIndent(),
        )

        val result = client.playbackInfo(requireTranscode = false)

        assertEquals("direct", result.mediaSourceId)
        assertEquals(4096, result.totalLength)
    }

    @Test
    fun forcedTranscodeSkipsDirectOnlySources() = runBlocking {
        val client = client(
            """
            {
              "MediaSources": [
                {
                  "Id": "direct-only",
                  "Container": "mp4",
                  "SupportsDirectPlay": true,
                  "DirectStreamUrl": "/Videos/direct-only/stream.mp4"
                },
                {
                  "Id": "transcode",
                  "Container": "mkv",
                  "SupportsTranscoding": true,
                  "TranscodingUrl": "/Videos/transcode/stream.mp4",
                  "TranscodingContainer": "mp4"
                }
              ]
            }
            """.trimIndent(),
        )

        val result = client.playbackInfo(requireTranscode = true)

        assertEquals("transcode", result.mediaSourceId)
        assertEquals(null, result.totalLength)
    }

    @Test
    fun rejectsAResponseWithNoUsableMediaSource() = runBlocking {
        val client = client("""{ "MediaSources": [{ "Id": "offline" }] }""")

        assertFailsWith<IllegalStateException> {
            client.playbackInfo(requireTranscode = false)
        }
        Unit
    }

    private suspend fun JellyfinClient.playbackInfo(requireTranscode: Boolean) = postPlaybackInfo(
        itemId = "item-1",
        deviceProfileJson = "{}",
        audioStreamIndex = null,
        subtitleStreamIndex = null,
        maxBitrate = null,
        startTimeTicks = 0,
        requireTranscode = requireTranscode,
    )

    private fun client(responseBody: String): JellyfinClient {
        val responder = FixedResponseInterceptor(responseBody)
        return JellyfinClient(
            httpClient = OkHttpClient.Builder().addInterceptor(responder).build(),
            deviceId = "test-device",
            deviceName = "Test device",
            appVersion = "test",
            logger = NoopLogger,
        ).apply {
            configureServer("https://jellyfin.example.test")
            setAuth("test-token", "user-1")
        }
    }

    private class FixedResponseInterceptor(private val body: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.encodedPath != "/Items/item-1/PlaybackInfo") {
                throw IOException("Unexpected request: ${request.url}")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(JSON))
                .build()
        }
    }

    private object NoopLogger : DiagnosticSink

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
