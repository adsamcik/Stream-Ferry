package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinNegotiationIsolationTest {
    @Test
    fun `interleaved playback download and seek retain their own upstream and track selection`() = runBlocking {
        for (protocol in Protocol.entries) {
            var session = 0
            val responder = Interceptor { chain ->
                val request = chain.request()
                val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                val ticks = body.getValue("StartTimeTicks").jsonPrimitive.content
                val audio = body.getValue("AudioStreamIndex").jsonPrimitive.content
                val subtitle = body.getValue("SubtitleStreamIndex").jsonPrimitive.content
                val output = if (body["MaxStreamingBitrate"]?.jsonPrimitive?.content == "2000000") "mp4" else "ts"
                val id = "session-${++session}"
                val json = """{"PlaySessionId":"$id","MediaSources":[{
                    "Id":"version","Container":"mkv","SupportsTranscoding":true,
                    "TranscodingContainer":"$output",
                    "TranscodingUrl":"/Videos/version/stream.$output?session=$id&start=$ticks&audio=$audio&subtitle=$subtitle",
                    "MediaStreams":[{"Type":"Audio","Index":1,"Codec":"aac"},
                    {"Type":"Audio","Index":2,"Codec":"ac3"},{"Type":"Subtitle","Index":3,"Codec":"ass"}]}]}"""
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }
            val http = OkHttpClient.Builder().addInterceptor(responder).build()
            val client = JellyfinClient(http, "device", "Device", "test", NoopLogger).apply {
                configureServer("https://jellyfin.example")
                setAuth("token", "user")
            }
            val repository = HttpJellyfinRepository(client, NoopLogger, http)
            val capabilities = TargetCapabilities(
                protocol = protocol, supportedContainers = setOf("mp4", "ts"),
                supportedVideoCodecs = setOf("h264"), supportedAudioCodecs = setOf("aac"),
            )
            suspend fun negotiate(position: Long, audio: Int, subtitle: Int, download: Boolean = false) =
                repository.playbackInfo(
                    "item", capabilities, if (download) 2_000_000 else null, true, true, audio, subtitle, position,
                    downloadProfile = if (download) JellyfinDownloadTranscodeProfile(2_000_000, "mp4", "h264", "aac") else null,
                    mediaSourceId = "version",
                ).getOrThrow()

            // Delay resolution until all three negotiations for the same source have completed. A
            // shared media-source cache replaces the first two locators with the seek's locator.
            val playback = negotiate(45, 1, 3)
            val download = negotiate(0, 2, -1, download = true)
            val seek = negotiate(90, 2, 3)
            for ((info, expectedContainer, expectedSelection) in listOf(
                Triple(playback, "ts", "start=450000000&audio=1&subtitle=3"),
                Triple(download, "mp4", "start=0&audio=2&subtitle=-1"),
                Triple(seek, "ts", "start=900000000&audio=2&subtitle=3"),
            )) {
                val upstream = repository.resolveUpstream(info)
                assertTrue(upstream.url.contains("session=${info.playSessionId}"), upstream.url)
                assertTrue(upstream.url.contains(expectedSelection), upstream.url)
                assertEquals(expectedContainer, upstream.outputContainer)
                assertTrue(upstream.authHeader.orEmpty().contains("token"))
            }
            assertEquals(listOf(1), playback.audioTracks.filter { it.isDefault }.map { it.index })
            assertEquals(listOf(2), download.audioTracks.filter { it.isDefault }.map { it.index })
        }
    }

    private object NoopLogger : DiagnosticSink
}
