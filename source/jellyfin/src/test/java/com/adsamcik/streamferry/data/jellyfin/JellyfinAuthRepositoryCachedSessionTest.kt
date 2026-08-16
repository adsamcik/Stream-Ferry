package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.data.security.ServerConfigStore
import com.adsamcik.streamferry.data.security.StoredServer
import com.adsamcik.streamferry.domain.SecureTokenStore
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Security contract for cache-only restores: the saved token proves ownership of the local cache, but
 * is never installed or attached to a request until public discovery verifies the pinned server id.
 */
class JellyfinAuthRepositoryCachedSessionTest {

    @Test
    fun offlineRestore_keepsTokenlessCachedSession_withoutInstallingOrSendingToken() = runBlocking {
        val fixture = fixture(publicInfoResponse = "{not-json")

        assertNull(fixture.repository.restoreSession())
        assertEquals(UserSession(USER_ID, SERVER_ID), fixture.repository.cachedSession.value)
        assertNull(fixture.repository.currentUser.value)
        assertFalse(fixture.client.isAuthenticated)
        assertTokenlessPublicInfoRequest(fixture)
    }

    @Test
    fun verifiedIdentityMatch_restoresAuthenticatedSession_afterTokenlessDiscovery() = runBlocking {
        val fixture = fixture(publicInfoResponse = """{"Id":"$SERVER_ID","ServerName":"Test Jellyfin"}""")

        assertEquals(UserSession(USER_ID, SERVER_ID), fixture.repository.restoreSession())
        assertEquals(UserSession(USER_ID, SERVER_ID), fixture.repository.currentUser.value)
        assertNull(fixture.repository.cachedSession.value)
        assertTrue(fixture.client.isAuthenticated)
        assertEquals(USER_ID, fixture.client.userId)
        assertTokenlessPublicInfoRequest(fixture)
    }

    @Test
    fun verifiedIdentityMismatch_clearsCachedSession_withoutInstallingOrSendingToken() = runBlocking {
        val fixture = fixture(publicInfoResponse = """{"Id":"other-server","ServerName":"Unexpected Jellyfin"}""")

        assertNull(fixture.repository.restoreSession())
        assertNull(fixture.repository.cachedSession.value)
        assertNull(fixture.repository.currentUser.value)
        assertFalse(fixture.client.isAuthenticated)
        assertTokenlessPublicInfoRequest(fixture)
    }

    private fun assertTokenlessPublicInfoRequest(fixture: Fixture) {
        assertEquals(1, fixture.responder.requests.size)
        val request = fixture.responder.requests.single()
        assertEquals("/System/Info/Public", request.url.encodedPath)
        assertNull(request.header("Authorization"))
        assertFalse(request.url.toString().contains(TOKEN))
    }

    private fun fixture(publicInfoResponse: String): Fixture {
        val profile = StoredServer(
            serverId = SERVER_ID,
            baseUrl = "https://jellyfin.example.test",
            name = "Test Jellyfin",
            userId = USER_ID,
        )
        val configStore = mockk<ServerConfigStore>()
        every { configStore.active() } returns profile
        val responder = RecordingResponder(publicInfoResponse)
        val client = JellyfinClient(
            httpClient = OkHttpClient.Builder().addInterceptor(responder).build(),
            deviceId = "test-device",
            deviceName = "Test device",
            appVersion = "test",
            logger = NoopLogger,
        )
        return Fixture(
            repository = JellyfinAuthRepository(
                client = client,
                tokenStore = InMemoryTokenStore(mapOf(SERVER_ID to TOKEN)),
                configStore = configStore,
                logger = NoopLogger,
            ),
            client = client,
            responder = responder,
        )
    }

    private data class Fixture(
        val repository: JellyfinAuthRepository,
        val client: JellyfinClient,
        val responder: RecordingResponder,
    )

    private class RecordingResponder(private val body: String) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(JSON))
                .build()
        }
    }

    private class InMemoryTokenStore(tokens: Map<String, String>) : SecureTokenStore {
        private val entries = tokens.toMutableMap()

        override suspend fun put(serverId: String, token: String) {
            entries[serverId] = token
        }

        override suspend fun get(serverId: String): String? = entries[serverId]

        override suspend fun remove(serverId: String) {
            entries.remove(serverId)
        }

        override suspend fun clear() {
            entries.clear()
        }
    }

    private object NoopLogger : DiagnosticSink

    private companion object {
        const val SERVER_ID = "server-id"
        const val USER_ID = "user-id"
        const val TOKEN = "persisted-token-that-must-stay-local"
        val JSON = "application/json".toMediaType()
    }
}
