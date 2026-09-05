package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinMediaSegmentsTest {
    @Test fun `requests segments without invalid enum filter and maps supported types`() = runBlocking {
        val client = client(200, """{"Items":[
            {"Type":"Intro","StartTicks":100000000,"EndTicks":300000000},
            {"Type":"FutureType","StartTicks":400000000,"EndTicks":500000000}
        ]}""")
        val segments = client.mediaSegments("episode")
        assertEquals(1, segments.size)
        assertEquals(10L, segments.single().startSeconds)
        assertEquals(30L, segments.single().endSeconds)
    }

    @Test fun `older servers without segment endpoint do not prevent playback`() = runBlocking {
        assertEquals(emptyList(), client(404, "{}").mediaSegments("episode"))
    }

    private fun client(code: Int, body: String) = JellyfinClient(
        httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/MediaSegments/episode", request.url.encodedPath)
            assertEquals(null, request.url.query)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(),
        deviceId = "test", deviceName = "Test", appVersion = "test", logger = object : DiagnosticSink {},
    ).apply {
        configureServer("https://jellyfin.example.test")
        setAuth("test-token", "user")
    }
}
