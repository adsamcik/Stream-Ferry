package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.data.security.ServerConfigStore
import com.adsamcik.streamferry.data.security.StoredServer
import com.adsamcik.streamferry.domain.SecureTokenStore
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinCredentialEndpointTest {
    @Test
    fun `guarded metadata batch keeps token and endpoint stable while profile switch is queued`() = runBlocking {
        val fixture = Fixture()
        val session = UserSession("user", "server")
        assertEquals(session, fixture.repository.restoreSession())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batch = async {
            fixture.repository.runWithActiveSession(session) {
                started.complete(Unit)
                release.await()
                fixture.client.itemsByIds(listOf("movie"))
            }
        }
        started.await()
        val switching = async { fixture.repository.setServer("https://other.example", false) }
        yield()
        assertFalse(switching.isCompleted)
        assertTrue(fixture.requests.all { it.url.host == "original.example" })
        release.complete(Unit)
        assertTrue(batch.await())
        assertTrue(switching.await().isFailure)
        val mediaRequest = fixture.requests.single { it.url.encodedPath == "/Items" }
        assertEquals("original.example", mediaRequest.url.host)
        assertTrue(mediaRequest.header("Authorization").orEmpty().contains("original-token"))
        var staleCalled = false
        assertFalse(fixture.repository.runWithActiveSession(session) { staleCalled = true })
        assertFalse(staleCalled)
    }

    @Test
    fun `same public server ID at another endpoint cannot replace profile or inherit token`() = runBlocking {
        for (candidate in listOf("https://other.example", "https://original.example:8443", "https://original.example/other", "http://original.local")) {
            // Approved HTTP is valid only on LAN endpoints. Use a private hostname so the scheme-change
            // case actually reaches the credential endpoint guard, rather than failing URL validation.
            val originalBase = if (candidate.startsWith("http://")) "https://original.local" else "https://original.example"
            val fixture = Fixture(base = originalBase)
            val original = fixture.profile

            val result = fixture.repository.setServer(candidate, userApprovedHttp = true)

            assertTrue(result.isFailure, candidate)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("saved profile was kept"), "$candidate: ${result.exceptionOrNull()}")
            assertEquals(original, fixture.profile)
            assertEquals("server", fixture.active)
            assertEquals("original-token", fixture.tokens.get("server"))
            assertFalse(fixture.client.isAuthenticated)
            assertTrue(fixture.requests.all { it.header("Authorization") == null })

            // Selection/restoration still reaches the original endpoint with its original credential.
            assertEquals(UserSession("user", "server"), fixture.repository.switchServer("server"))
            assertEquals(originalBase, fixture.client.baseUrl)
            assertTrue(fixture.client.isAuthenticated)
            assertTrue(fixture.requests.all { it.header("Authorization") == null })
        }
    }

    @Test
    fun `legacy credentials require fresh login then survive restart only at authenticated endpoint`() = runBlocking {
        val fixture = Fixture(bound = false)
        assertNull(fixture.repository.restoreSession())
        assertEquals(UserSession("user", "server"), fixture.repository.cachedSession.value)
        assertEquals("original-token", fixture.tokens.get("server"))
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.repository.ensureOnlineSession().exceptionOrNull() is JellyfinSignInRequiredException)

        fixture.repository.setServer("https://original.example", userApprovedHttp = false).getOrThrow()
        assertEquals(UserSession("user", "server"), fixture.repository.login("user", "password").getOrThrow())
        assertEquals("https://original.example", fixture.profile.credentialBaseUrl)
        assertEquals("fresh-token", fixture.tokens.get("server"))

        val restarted = JellyfinAuthRepository(fixture.client, fixture.tokens, fixture.config, NoopLogger)
        assertEquals(UserSession("user", "server"), restarted.restoreSession())
        assertTrue(fixture.requests.all { it.url.host == "original.example" })
        assertTrue(fixture.requests.filter { it.url.encodedPath == "/System/Info/Public" }.all { it.header("Authorization") == null })
    }

    @Test
    fun `selecting signed out profile configures its endpoint before fresh authentication`() = runBlocking {
        val fixture = Fixture()
        fixture.profile = fixture.profile.copy(userId = null, credentialBaseUrl = null)
        fixture.client.configureServer("https://previous.example")

        assertNull(fixture.repository.switchServer("server"))
        assertEquals("https://original.example", fixture.client.baseUrl)
        assertTrue(fixture.repository.ensureOnlineSession().exceptionOrNull() is JellyfinSignInRequiredException)
        assertEquals(UserSession("user", "server"), fixture.repository.login("user", "password").getOrThrow())
        assertTrue(fixture.requests.all { it.url.host == "original.example" })
    }

    private class Fixture(bound: Boolean = true, base: String = "https://original.example") {
        var profile = StoredServer("server", base, "Original", "user", if (bound) base else null)
        var active = "server"
        val tokens = Tokens()
        val config = mockk<ServerConfigStore>().apply {
            val store = this
            every { store.get("server") } answers { this@Fixture.profile }
            every { store.active() } answers { this@Fixture.profile }
            every { store.activeId() } answers { this@Fixture.active }
            every { store.servers() } answers { listOf(this@Fixture.profile) }
            coEvery { store.upsert(any(), any()) } coAnswers { this@Fixture.profile = firstArg() }
            coEvery { store.setActive(any()) } coAnswers { this@Fixture.active = firstArg() }
        }
        val requests = mutableListOf<Request>()
        private val responder = Interceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/Users/AuthenticateByName" -> """{"AccessToken":"fresh-token","User":{"Id":"user"}}"""
                "/Items" -> """{"Items":[]}"""
                else -> """{"Id":"server","ServerName":"Original"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val client = JellyfinClient(OkHttpClient.Builder().addInterceptor(responder).build(), "device", "Device", "test", NoopLogger)
        val repository = JellyfinAuthRepository(client, tokens, config, NoopLogger)
    }

    private class Tokens : SecureTokenStore {
        private val values = mutableMapOf("server" to "original-token")
        override suspend fun put(serverId: String, token: String) { values[serverId] = token }
        override suspend fun get(serverId: String) = values[serverId]
        override suspend fun remove(serverId: String) { values.remove(serverId) }
        override suspend fun clear() = values.clear()
    }

    private object NoopLogger : DiagnosticSink
}
