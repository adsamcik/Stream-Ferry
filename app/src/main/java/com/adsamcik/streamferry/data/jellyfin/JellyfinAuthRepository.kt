package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.net.ServerUrlValidator
import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.server.ServerIdentity
import com.adsamcik.streamferry.data.security.ServerConfigStore
import com.adsamcik.streamferry.data.security.StoredServer
import com.adsamcik.streamferry.domain.AuthRepository
import com.adsamcik.streamferry.domain.SecureTokenStore
import com.adsamcik.streamferry.domain.ServerProfile
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Quick Connect code bound to the exact server configuration that issued it. The user-facing code
 * is safe for the UI; the secret stays inside the data layer and is never passed as a standalone value.
 */
internal data class QuickConnectSession(
    val code: String,
    internal val handshake: QuickConnectHandshake,
    internal val serverId: String,
    internal val generation: Long,
)

/** Multiple Jellyfin servers, with a stable server-Id verified before a persisted token is reused. */
class JellyfinAuthRepository(
    private val client: JellyfinClient,
    private val tokenStore: SecureTokenStore,
    private val configStore: ServerConfigStore,
    private val logger: DiagnosticsLogger,
) : AuthRepository {

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    override val currentUser = _currentUser.asStateFlow()
    /**
     * A tokenless identity used only to scope cached metadata and completed downloads while the server
     * cannot be verified. The stored token is deliberately never installed into [client] in this mode.
     */
    private val _cachedSession = MutableStateFlow<UserSession?>(null)
    val cachedSession = _cachedSession.asStateFlow()

    /** All client/session mutations are atomic against a monotonically increasing auth generation. */
    private val stateLock = Any()
    private var stateGeneration = 0L
    @Volatile private var serverId: String? = null
    /** Serializes active-profile writes, which may suspend in encrypted preferences. */
    private val persistenceMutex = Mutex()
    /**
     * The client holds its configured origin and Authorization header as one mutable session. Hold this
     * across every operation that can configure it, submit credentials, or mutate persisted tokens so a
     * server switch can never retarget an in-flight password/Quick Connect request or resurrect a token.
     */
    private val authOperationMutex = Mutex()

    private data class CredentialOperation(val generation: Long, val serverId: String)

    /** Non-secret identity retained solely for cache and completed-download scoping. */
    private data class CachedSessionOperation(
        val generation: Long,
        val serverId: String,
        val userId: String,
    )

    /** A user action superseded a suspended auth operation; it must not alter the new server/session. */
    private class SupersededAuthOperation : IllegalStateException("A newer server or sign-in action superseded this request.")

    /** A live verification failed, while a scoped cache-only session may still be usable. */
    private class JellyfinServerUnavailableException : IllegalStateException(
        "Jellyfin is unavailable or the saved session cannot be verified.",
    )

    override suspend fun setServer(rawUrl: String, userApprovedHttp: Boolean): Result<ServerProfile> {
        return when (val v = ServerUrlValidator.validate(rawUrl, userApprovedHttp)) {
            is ServerUrlValidator.Result.Invalid -> Result.failure(IllegalArgumentException(v.reason))
            is ServerUrlValidator.Result.NeedsHttpApproval -> Result.failure(HttpApprovalRequiredException(v.baseUrl))
            is ServerUrlValidator.Result.Valid -> authOperationMutex.withLock {
                runCatching {
                    val generation = beginServerConfiguration(v.baseUrl)
                    val discovery = client.discoverServer()
                        ?: throw IllegalStateException("Couldn't reach a Jellyfin server at that address.")
                    val id = discovery.info.serverId?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("This server did not provide a stable identity.")
                    // Pin the final canonical discovery origin only if this operation is still current.
                    if (!adoptDiscoveryIfCurrent(generation, id, discovery.canonicalBaseUrl)) throw SupersededAuthOperation()
                    persistConfiguredProfile(generation, id, discovery.canonicalBaseUrl, discovery.info.name)
                    requireCurrent(generation)
                    val loggedIn = tokenStore.get(id) != null
                    requireCurrent(generation)
                    logger.event("auth", "Server configured + reachable: ${discovery.info.name}")
                    ServerProfile(id, LogRedactor.redactUrl(discovery.canonicalBaseUrl), discovery.info.name, active = true, loggedIn = loggedIn)
                }.onFailure {
                    if (it !is SupersededAuthOperation) logger.w("auth", "Server configuration/reachability failed", it)
                }
            }
        }
    }

    override suspend fun testConnection(): Result<String> = authOperationMutex.withLock {
        runCatching {
            client.systemInfoPublic() ?: throw IllegalStateException("No response from the server.")
        }.onFailure { logger.w("auth", "Connection test failed", it) }
    }

    override suspend fun login(username: String, password: String): Result<UserSession> = authOperationMutex.withLock {
        runCatching {
            val operation = beginCredentialOperation()
            // Keep the client origin stable from operation creation through the credential POST and
            // token persistence. A queued server switch runs only after this request has finished.
            persistAuth(client.authenticateByName(username, password), operation)
        }.onFailure {
            if (it !is SupersededAuthOperation) logger.w("auth", "Login failed (${it.javaClass.simpleName})", it)
        }
    }

    suspend fun quickConnectEnabled(): Boolean = authOperationMutex.withLock {
        client.isConfigured && client.quickConnectEnabled()
    }

    /**
     * Issue a code bound to the current server identity and auth generation. A later server switch,
     * logout, or replacement Quick Connect start invalidates this object before its secret can be polled
     * or exchanged anywhere else.
     */
    internal suspend fun startQuickConnect(): Result<QuickConnectSession> = authOperationMutex.withLock {
        runCatching {
            val operation = beginQuickConnectOperation()
            val handshake = client.quickConnectInitiate()
            requireCurrent(operation.generation, operation.serverId)
            QuickConnectSession(
                code = handshake.code,
                handshake = handshake,
                serverId = operation.serverId,
                generation = operation.generation,
            )
        }.onFailure {
            if (it !is SupersededAuthOperation) logger.w("auth", "Quick Connect initiate failed", it)
        }
    }

    /** Poll only the server that created this code; never send a stale secret after a server change. */
    internal suspend fun pollQuickConnect(session: QuickConnectSession): Result<Boolean> = authOperationMutex.withLock {
        runCatching {
            requireCurrent(session.generation, session.serverId)
            client.quickConnectPoll(session.handshake.secret)
        }
    }

    /** Exchange only a still-current code, then install its resulting token through the normal guard. */
    internal suspend fun completeQuickConnect(session: QuickConnectSession): Result<UserSession> = authOperationMutex.withLock {
        runCatching {
            requireCurrent(session.generation, session.serverId)
            val operation = beginCredentialOperation()
            if (operation.serverId != session.serverId) throw SupersededAuthOperation()
            persistAuth(client.authenticateWithQuickConnect(session.handshake.secret), operation)
        }.onFailure {
            if (it !is SupersededAuthOperation) logger.w("auth", "Quick Connect completion failed", it)
        }
    }

    /** Store and install a fresh token only if the exact server operation that requested it is still live. */
    private suspend fun persistAuth(auth: JellyfinApi.AuthResult, operation: CredentialOperation): UserSession {
        requireCurrent(operation.generation, operation.serverId)
        // Persisting a token for the server that actually issued it is harmless if a later action wins the
        // race; the guarded installation below is what prevents it ever being attached to another origin.
        tokenStore.put(operation.serverId, auth.accessToken)
        val session = installAuthIfCurrent(operation, auth) ?: throw SupersededAuthOperation()
        persistenceMutex.withLock {
            if (isCurrent(operation.generation, operation.serverId)) {
                configStore.get(operation.serverId)?.let {
                    configStore.upsert(it.copy(userId = auth.userId), makeActive = false)
                }
            }
        }
        if (isCurrent(operation.generation, operation.serverId)) logger.event("auth", "Login succeeded")
        return session
    }

    override suspend fun logout() = authOperationMutex.withLock {
        val id = synchronized(stateLock) {
            stateGeneration += 1
            val currentId = serverId
            // Keep the verified server identity so the user can immediately sign in again, but remove
            // every in-memory credential before the keystore operation suspends.
            client.clearAuth()
            _currentUser.value = null
            _cachedSession.value = null
            currentId
        }
        id?.let { tokenStore.remove(it) }
        Unit
    }

    override suspend fun deleteServerProfile(serverId: String) = authOperationMutex.withLock {
        val wasCurrent = synchronized(stateLock) {
            if (this.serverId != serverId) false else {
                stateGeneration += 1
                client.clearAll()
                this.serverId = null
                _currentUser.value = null
                _cachedSession.value = null
                true
            }
        }
        tokenStore.remove(serverId)
        persistenceMutex.withLock { configStore.remove(serverId) }
        if (wasCurrent) logger.event("auth", "Active server profile deleted")
    }

    override suspend fun deleteAllData() = authOperationMutex.withLock {
        synchronized(stateLock) {
            stateGeneration += 1
            client.clearAll()
            serverId = null
            _currentUser.value = null
            _cachedSession.value = null
        }
        tokenStore.clear()
        persistenceMutex.withLock { configStore.clear() }
    }

    override suspend fun servers(): List<ServerProfile> {
        val active = configStore.activeId()
        return configStore.servers().map { s ->
            ServerProfile(
                s.serverId,
                LogRedactor.redactUrl(s.baseUrl),
                s.name,
                active = s.serverId == active,
                loggedIn = tokenStore.get(s.serverId) != null,
            )
        }
    }

    override suspend fun switchServer(serverId: String): UserSession? = authOperationMutex.withLock {
        val generation = synchronized(stateLock) {
            stateGeneration += 1
            client.clearAuth()
            this.serverId = null
            _currentUser.value = null
            _cachedSession.value = null
            stateGeneration
        }
        persistenceMutex.withLock {
            requireCurrent(generation)
            configStore.setActive(serverId)
            requireCurrent(generation)
        }
        if (!isCurrent(generation)) return@withLock null
        restoreSessionLocked(expectedServerId = serverId)
    }

    /**
     * Restore a non-secret identity for the active profile without sending or installing its token.
     * It permits only scoped cached-library and verified-download access while connectivity is unknown.
     */
    suspend fun restoreCachedSession(): UserSession? = authOperationMutex.withLock {
        currentSessionForActiveProfile()
            ?: restoreCachedSessionLocked(expectedServerId = null)?.let { UserSession(it.userId, it.serverId) }
    }

    /** Restore the active server only after fresh unauthenticated discovery verifies the stored identity. */
    suspend fun restoreSession(): UserSession? = authOperationMutex.withLock {
        currentSessionForActiveProfile() ?: restoreSessionLocked(expectedServerId = null)
    }

    /** Attempt live verification while retaining a cache-only identity if the server is unreachable. */
    suspend fun ensureOnlineSession(): Result<UserSession> = authOperationMutex.withLock {
        (currentSessionForActiveProfile() ?: restoreSessionLocked(expectedServerId = null))
            ?.let { Result.success(it) }
            ?: Result.failure(JellyfinServerUnavailableException())
    }

    /**
     * Execute an authenticated request only while the singleton [client] remains bound to [expected].
     * Holding the same mutex as server switching/logout prevents a suspended request from picking up a
     * different server's URL or token before it builds its HTTP request.
     *
     * @return false when the expected verified session was superseded before the request began.
     */
    suspend fun runWithActiveSession(expected: UserSession, action: suspend () -> Unit): Boolean =
        authOperationMutex.withLock {
            if (currentSessionForActiveProfile() != expected || client.userId != expected.userId) {
                return@withLock false
            }
            action()
            // Nothing else can rebind the client while the mutex is held, but retain this check as a
            // fail-closed boundary if future session management gains another mutation path.
            _currentUser.value == expected && client.userId == expected.userId
        }

    /** Caller holds [authOperationMutex], keeping the mutable client bound to this saved profile. */
    private suspend fun restoreSessionLocked(expectedServerId: String?): UserSession? {
        val cached = restoreCachedSessionLocked(expectedServerId) ?: return null
        // Never send a persisted token until a fresh unauthenticated discovery confirms the server
        // identity. A timeout, malformed response, or missing Id is fail-closed: the token remains
        // uninstalled, while the non-secret cache scope stays available for offline browsing.
        val discoveredId = client.publicInfo()?.serverId?.takeIf { it.isNotBlank() }
        if (!isCurrent(cached.generation, cached.serverId)) return null
        if (discoveredId == null) {
            logger.event("auth", "Server unavailable; retaining tokenless cached library session")
            return null
        }
        if (ServerIdentity.isMismatch(cached.serverId, discoveredId)) {
            if (clearAuthIfCurrent(cached.generation, cached.serverId)) {
                logger.w("auth", "Server identity mismatch — refusing token and cached session (possible spoof); profile kept")
            }
            return null
        }
        val token = tokenStore.get(cached.serverId) ?: return null
        if (!isCurrent(cached.generation, cached.serverId)) return null
        val operation = CredentialOperation(cached.generation, cached.serverId)
        val session = installAuthIfCurrent(operation, JellyfinApi.AuthResult(token, cached.userId, serverVersion = null))
            ?: return null
        logger.event("auth", "Session restored")
        return session
    }

    /** Caller holds [authOperationMutex] and checks whether an already verified session is still active. */
    private suspend fun currentSessionForActiveProfile(): UserSession? {
        val current = _currentUser.value ?: return null
        val active = configStore.active() ?: return null
        return current.takeIf { it.serverId == active.serverId && it.userId == active.userId }
    }

    /** Caller holds [authOperationMutex]. This must never install a token into [client]. */
    private suspend fun restoreCachedSessionLocked(expectedServerId: String?): CachedSessionOperation? {
        val saved = configStore.active() ?: return null
        if (expectedServerId != null && saved.serverId != expectedServerId) return null
        val userId = saved.userId ?: return null
        // A cached profile is only meaningful when an account token remains stored for this exact server.
        if (tokenStore.get(saved.serverId) == null) return null
        val generation = beginRestore(saved)
        if (!isCurrent(generation, saved.serverId)) return null
        val published = synchronized(stateLock) {
            if (stateGeneration != generation || serverId != saved.serverId) false else {
                _cachedSession.value = UserSession(userId, saved.serverId)
                true
            }
        }
        return if (published) CachedSessionOperation(generation, saved.serverId, userId) else null
    }

    /** Configure a new unauthenticated candidate and invalidate every previous suspended operation. */
    private fun beginServerConfiguration(baseUrl: String): Long = synchronized(stateLock) {
        stateGeneration += 1
        client.configureServer(baseUrl)
        serverId = null
        _currentUser.value = null
        _cachedSession.value = null
        stateGeneration
    }

    private fun beginRestore(saved: StoredServer): Long = synchronized(stateLock) {
        stateGeneration += 1
        client.configureServer(saved.baseUrl)
        serverId = saved.serverId
        _currentUser.value = null
        _cachedSession.value = null
        stateGeneration
    }

    private fun beginCredentialOperation(): CredentialOperation = synchronized(stateLock) {
        check(client.isConfigured) { "Set the server address first." }
        val id = serverId ?: error("Set and verify the server address before signing in.")
        stateGeneration += 1
        CredentialOperation(stateGeneration, id)
    }

    /** Begin a new device-code flow and invalidate any older pending Quick Connect secret. */
    private fun beginQuickConnectOperation(): CredentialOperation = synchronized(stateLock) {
        check(client.isConfigured) { "Set the server address first." }
        val id = serverId ?: error("Set and verify the server address before starting Quick Connect.")
        stateGeneration += 1
        CredentialOperation(stateGeneration, id)
    }

    private fun adoptDiscoveryIfCurrent(generation: Long, id: String, canonicalBaseUrl: String): Boolean = synchronized(stateLock) {
        if (stateGeneration != generation) return@synchronized false
        client.adoptDiscoveredServer(canonicalBaseUrl)
        serverId = id
        true
    }

    private fun installAuthIfCurrent(operation: CredentialOperation, auth: JellyfinApi.AuthResult): UserSession? = synchronized(stateLock) {
        if (stateGeneration != operation.generation || serverId != operation.serverId) return@synchronized null
        client.setAuth(auth.accessToken, auth.userId)
        UserSession(auth.userId, operation.serverId).also {
            _currentUser.value = it
            _cachedSession.value = null
        }
    }

    private fun clearAuthIfCurrent(generation: Long, expectedServerId: String): Boolean = synchronized(stateLock) {
        if (stateGeneration != generation || serverId != expectedServerId) return@synchronized false
        client.clearAuth()
        _currentUser.value = null
        _cachedSession.value = null
        true
    }

    private fun isCurrent(generation: Long, expectedServerId: String? = null): Boolean = synchronized(stateLock) {
        stateGeneration == generation && (expectedServerId == null || serverId == expectedServerId)
    }

    private fun requireCurrent(generation: Long, expectedServerId: String? = null) {
        if (!isCurrent(generation, expectedServerId)) throw SupersededAuthOperation()
    }

    private suspend fun persistConfiguredProfile(generation: Long, id: String, baseUrl: String, name: String) {
        persistenceMutex.withLock {
            requireCurrent(generation, id)
            val priorUserId = configStore.get(id)?.userId
            requireCurrent(generation, id)
            // Writing without activation first means a newer server-selection action always gets the
            // final active-profile decision, even if this encrypted write overlaps it.
            configStore.upsert(StoredServer(id, baseUrl, name, priorUserId), makeActive = false)
            requireCurrent(generation, id)
            configStore.setActive(id)
            requireCurrent(generation, id)
        }
    }
}

class HttpApprovalRequiredException(val baseUrl: String) : RuntimeException("This LAN http address must be explicitly approved before use.")
