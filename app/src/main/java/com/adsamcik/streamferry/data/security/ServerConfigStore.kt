package com.adsamcik.streamferry.data.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A stored server profile. The access TOKEN is never here — it lives in KeystoreTokenStore, keyed by [serverId]. */
@Serializable
data class StoredServer(
    val serverId: String,
    val baseUrl: String,
    val name: String,
    val userId: String? = null,
)

/**
 * Persists ALL known server profiles (encrypted at rest) so the app remembers every server even when one
 * is temporarily unreachable (e.g. you left the home network) — profiles are removed only on explicit
 * "Forget". [serverId] is the server real Jellyfin Id (anti-spoof pin), so the same id keeps pointing at
 * the same server across URLs. Cleared by "Delete all app data".
 */
class ServerConfigStore(context: Context) {

    private val prefs = SecurePreferences(context, FILE_NAME, KEY_ALIAS)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun servers(): List<StoredServer> = prefs.getString(KEY_SERVERS)
        ?.let { runCatching { json.decodeFromString(ListSerializer(StoredServer.serializer()), it) }.getOrNull() } ?: emptyList()

    fun activeId(): String? = prefs.getString(KEY_ACTIVE)?.ifBlank { null }
    fun active(): StoredServer? = activeId()?.let { id -> servers().firstOrNull { it.serverId == id } }
    fun get(serverId: String): StoredServer? = servers().firstOrNull { it.serverId == serverId }

    suspend fun upsert(server: StoredServer, makeActive: Boolean = true) = withContext(Dispatchers.IO) {
        write(servers().filterNot { it.serverId == server.serverId } + server)
        if (makeActive || activeId() == null) prefs.putString(KEY_ACTIVE, server.serverId)
    }

    suspend fun setActive(serverId: String) = withContext(Dispatchers.IO) { prefs.putString(KEY_ACTIVE, serverId) }

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        val remaining = servers().filterNot { it.serverId == serverId }
        write(remaining)
        if (activeId() == serverId) prefs.putString(KEY_ACTIVE, remaining.firstOrNull()?.serverId ?: "")
    }

    private fun write(list: List<StoredServer>) =
        prefs.putString(KEY_SERVERS, json.encodeToString(ListSerializer(StoredServer.serializer()), list))

    suspend fun clear() = withContext(Dispatchers.IO) { prefs.clear() }

    companion object {
        private const val FILE_NAME = "jellyfin_bridge_server_v2"
        private const val KEY_ALIAS = "jellyfin_bridge_config_key"
        private const val KEY_SERVERS = "servers_json"
        private const val KEY_ACTIVE = "active_server_id"
    }
}