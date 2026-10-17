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
import okio.Buffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun pinsTheDiscoveredMediaSourceWhenSelectingOrDisablingSubtitles() = runBlocking {
        for (selected in listOf(-1, 3)) {
            val bodies = mutableListOf<String>()
            val responder = FixedResponseInterceptor(
                """{"MediaSources":[{"Id":"version-2","Container":"mkv",
                  "SupportsTranscoding":true,"TranscodingUrl":"/Videos/version-2/stream.ts",
                  "TranscodingContainer":"ts","MediaStreams":[{"Type":"Subtitle","Index":3,"Codec":"ass"}]}]}""",
                bodies,
            )
            val client = client(responder)

            client.postPlaybackInfo("item-1", "{}", null, selected, null, 0, true)

            assertEquals(2, bodies.size)
            val discovery = Json.parseToJsonElement(bodies[0]).jsonObject
            val selection = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals(null, discovery["MediaSourceId"])
            assertEquals("version-2", selection["MediaSourceId"]?.jsonPrimitive?.content)
            assertEquals(selected.toString(), selection["SubtitleStreamIndex"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun failsInsteadOfSwitchingVersionsWhenThePinnedSourceDisappears() = runBlocking {
        var requests = 0
        val client = client(Interceptor { chain ->
            requests++
            FixedResponseInterceptor(
                """{"MediaSources":[{"Id":"${if (requests == 1) "chosen" else "other"}",
                  "Container":"mp4","SupportsDirectPlay":true}]}""",
            ).intercept(chain)
        })

        assertFailsWith<IllegalStateException> {
            client.postPlaybackInfo("item-1", "{}", null, -1, null, 0, false)
        }
        Unit
    }

    @Test
    fun failsInsteadOfIgnoringASubtitleMissingFromTheSelectedVersion() = runBlocking {
        val client = client("""{"MediaSources":[{"Id":"chosen","Container":"mp4","SupportsDirectPlay":true}]}""")

        assertFailsWith<IllegalStateException> {
            client.postPlaybackInfo("item-1", "{}", null, 7, null, 0, false)
        }
        Unit
    }

    @Test
    fun distinguishesExternalSidecarsFromEmbeddedSubtitlePackets() = runBlocking {
        for (external in listOf(true, false)) {
            val client = client(
                """{"MediaSources":[{"Id":"chosen","Container":"mkv","SupportsDirectPlay":true,
                  "MediaStreams":[{"Type":"Subtitle","Index":3,"Codec":"subrip","IsExternal":$external}]}]}""",
            )

            val prepared = client.playbackInfo(requireTranscode = false)

            assertEquals(1, prepared.subtitleTracks.size)
            assertEquals(!external, prepared.hasEmbeddedSubtitleTracks)
        }
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
        return client(FixedResponseInterceptor(responseBody))
    }

    private fun client(responder: Interceptor): JellyfinClient {
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

    private class FixedResponseInterceptor(
        private val body: String,
        private val requests: MutableList<String>? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.encodedPath != "/Items/item-1/PlaybackInfo") {
                throw IOException("Unexpected request: ${request.url}")
            }
            requests?.add(Buffer().also { request.body?.writeTo(it) }.readUtf8())
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
