package com.videobridge.data.jellyfin

import com.videobridge.core.net.ServerUrlValidator
import com.videobridge.core.redaction.LogRedactor
import com.videobridge.core.server.ServerIdentity
import com.videobridge.data.security.ServerConfigStore
import com.videobridge.data.security.StoredServer
import com.videobridge.domain.AuthRepository
import com.videobridge.domain.SecureTokenStore
import com.videobridge.domain.ServerProfile
import com.videobridge.domain.UserSession
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Multiple Jellyfin servers, never forgotten when offline, with anti-spoof server-Id pinning (works on http). */
class JellyfinAuthRepository(
    private val client: JellyfinClient,
    private val tokenStore: SecureTokenStore,
    private val configStore: ServerConfigStore,
    private val logger: DiagnosticsLogger,
) : AuthRepository {

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    override val currentUser = _currentUser.asStateFlow()

    @Volatile private var serverId: String? = null

    override suspend fun setServer(rawUrl: String, userApprovedHttp: Boolean): Result<ServerProfile> {
        return when (val v = ServerUrlValidator.validate(rawUrl, userApprovedHttp)) {
            is ServerUrlValidator.Result.Invalid -> Result.failure(IllegalArgumentException(v.reason))
            is ServerUrlValidator.Result.NeedsHttpApproval -> Result.failure(HttpApprovalRequiredException(v.baseUrl))
            is ServerUrlValidator.Result.Valid -> runCatching {
                client.configureServer(v.baseUrl)
                val info = client.publicInfo() ?: throw IllegalStateException("Couldn't reach a Jellyfin server at that address.")
                val id = info.serverId?.takeIf { it.isNotBlank() } ?: stableServerId(v.baseUrl)
                serverId = id
                configStore.upsert(StoredServer(id, v.baseUrl, info.name, configStore.get(id)?.userId), makeActive = true)
                logger.event("auth", "Server configured + reachable: ${info.name}")
                ServerProfile(id, LogRedactor.redactUrl(v.baseUrl), info.name, active = true, loggedIn = tokenStore.get(id) != null)
            }.onFailure { logger.w("auth", "Server configuration/reachability failed", it) }
        }
    }

    override suspend fun testConnection(): Result<String> = runCatching {
        client.systemInfoPublic() ?: throw IllegalStateException("No response from the server.")
    }.onFailure { logger.w("auth", "Connection test failed", it) }

    override suspend fun login(username: String, password: String): Result<UserSession> = runCatching {
        check(client.isConfigured) { "Set the server address first." }
        persistAuth(client.authenticateByName(username, password))
    }.onFailure { logger.w("auth", "Login failed (${it.javaClass.simpleName})", it) }

    suspend fun quickConnectEnabled(): Boolean = client.isConfigured && client.quickConnectEnabled()
    suspend fun startQuickConnect(): Result<QuickConnectHandshake> =
        runCatching { check(client.isConfigured); client.quickConnectInitiate() }
            .onFailure { logger.w("auth", "Quick Connect initiate failed", it) }
    suspend fun pollQuickConnect(secret: String): Result<Boolean> = runCatching { client.quickConnectPoll(secret) }
    suspend fun completeQuickConnect(secret: String): Result<UserSession> =
        runCatching { check(client.isConfigured); persistAuth(client.authenticateWithQuickConnect(secret)) }
            .onFailure { logger.w("auth", "Quick Connect completion failed", it) }

    private suspend fun persistAuth(auth: JellyfinApi.AuthResult): UserSession {
        client.setAuth(auth.accessToken, auth.userId)
        val id = serverId ?: stableServerId(client.baseUrl ?: "").also { serverId = it }
        tokenStore.put(id, auth.accessToken)
        configStore.get(id)?.let { configStore.upsert(it.copy(userId = auth.userId), makeActive = true) }
        val session = UserSession(auth.userId, id)
        _currentUser.value = session
        logger.event("auth", "Login succeeded")
        return session
    }

    override suspend fun logout() {
        serverId?.let { tokenStore.remove(it) }
        client.clearAuth()
        _currentUser.value = null
    }

    override suspend fun deleteServerProfile(serverId: String) {
        tokenStore.remove(serverId)
        configStore.remove(serverId)
        if (this.serverId == serverId) { client.clearAll(); this.serverId = null; _currentUser.value = null }
    }

    override suspend fun deleteAllData() {
        client.clearAll(); tokenStore.clear(); configStore.clear(); serverId = null; _currentUser.value = null
    }

    override suspend fun servers(): List<ServerProfile> {
        val active = configStore.activeId()
        return configStore.servers().map { s ->
            ServerProfile(s.serverId, LogRedactor.redactUrl(s.baseUrl), s.name, active = s.serverId == active, loggedIn = tokenStore.get(s.serverId) != null)
        }
    }

    override suspend fun switchServer(serverId: String): UserSession? {
        configStore.setActive(serverId)
        return restoreSession()
    }

    /** Restore the ACTIVE server: verify its pinned Id before sending the token; keep the profile regardless. */
    suspend fun restoreSession(): UserSession? {
        val saved = configStore.active() ?: return null
        client.configureServer(saved.baseUrl)
        serverId = saved.serverId
        val token = tokenStore.get(saved.serverId) ?: return null
        val uid = saved.userId ?: return null
        val info = client.publicInfo() // null = offline; trust stored token (no targeted-attack assumption)
        if (info != null && ServerIdentity.isMismatch(saved.serverId, info.serverId)) {
            logger.w("auth", "Server identity mismatch — refusing token (possible spoof); profile kept")
            client.clearAuth(); _currentUser.value = null; return null
        }
        client.setAuth(token, uid)
        val session = UserSession(uid, saved.serverId)
        _currentUser.value = session
        logger.event("auth", "Session restored")
        return session
    }

    private fun stableServerId(baseUrl: String): String = "srv_" + Integer.toHexString(baseUrl.hashCode())
}

class HttpApprovalRequiredException(val baseUrl: String) : RuntimeException("This LAN http address must be explicitly approved before use.")