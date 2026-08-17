package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinUserDataRequestTest {

    @Test
    fun resetProgressPostsExplicitUnwatchedStateToCurrentUserDataEndpoint() = runBlocking {
        val responder = RecordingResponder(listOf(200))
        val client = client(responder)

        client.resetProgress(ITEM_ID)

        val request = responder.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/UserItems/$ITEM_ID/UserData", request.url.encodedPath)
        assertEquals(USER_ID, request.url.queryParameter("userId"))
        val payload = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertEquals("0", payload["PlaybackPositionTicks"]?.jsonPrimitive?.content)
        assertEquals("false", payload["Played"]?.jsonPrimitive?.content)
    }

    @Test
    fun resetProgressRetriesOnlyTheLegacyUserDataRouteAfterEndpointNotFound() = runBlocking {
        val responder = RecordingResponder(listOf(404, 200))
        val client = client(responder)

        client.resetProgress(ITEM_ID)

        assertEquals(
            listOf(
                "/UserItems/$ITEM_ID/UserData",
                "/Users/$USER_ID/Items/$ITEM_ID/UserData",
            ),
            responder.requests.map { it.url.encodedPath },
        )
        assertEquals(null, responder.requests[1].url.queryParameter("userId"))
    }

    @Test
    fun markPlayedUsesCurrentUserPlayedItemsEndpoint() = runBlocking {
        val responder = RecordingResponder(listOf(200))
        val client = client(responder)

        client.markPlayed(ITEM_ID, played = true)

        val request = responder.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/UserPlayedItems/$ITEM_ID", request.url.encodedPath)
        assertEquals(USER_ID, request.url.queryParameter("userId"))
    }

    @Test
    fun markUnplayedUsesCurrentUserPlayedItemsEndpoint() = runBlocking {
        val responder = RecordingResponder(listOf(200))
        val client = client(responder)

        client.markPlayed(ITEM_ID, played = false)

        val request = responder.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/UserPlayedItems/$ITEM_ID", request.url.encodedPath)
        assertEquals(USER_ID, request.url.queryParameter("userId"))
    }

    @Test
    fun markPlayedRetriesLegacyRouteOnlyWhenCurrentEndpointIsUnavailable() = runBlocking {
        val responder = RecordingResponder(listOf(404, 200))
        val client = client(responder)

        client.markPlayed(ITEM_ID, played = true)

        assertEquals(
            listOf(
                "/UserPlayedItems/$ITEM_ID",
                "/Users/$USER_ID/PlayedItems/$ITEM_ID",
            ),
            responder.requests.map { it.url.encodedPath },
        )
        assertEquals(USER_ID, responder.requests.first().url.queryParameter("userId"))
        assertEquals(null, responder.requests.last().url.queryParameter("userId"))
    }
    private fun client(responder: RecordingResponder) = JellyfinClient(
        httpClient = OkHttpClient.Builder().addInterceptor(responder).build(),
        deviceId = "test-device",
        deviceName = "Test device",
        appVersion = "test",
        logger = NoopLogger,
    ).apply {
        configureServer("https://jellyfin.example.test")
        setAuth("test-token", USER_ID)
    }

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class RecordingResponder(codes: List<Int>) : Interceptor {
        private val responseCodes = codes.iterator()
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val code = if (responseCodes.hasNext()) responseCodes.next() else error("Unexpected request: ${request.url}")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Not found")
                .body("{}".toResponseBody(JSON))
                .build()
        }
    }

    private object NoopLogger : DiagnosticSink

    private companion object {
        const val USER_ID = "user-1"
        const val ITEM_ID = "episode-1"
        val JSON = "application/json".toMediaType()
    }
}
