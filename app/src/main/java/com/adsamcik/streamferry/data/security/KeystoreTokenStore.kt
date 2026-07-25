package com.adsamcik.streamferry.data.security

import android.content.Context
import com.adsamcik.streamferry.domain.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores Jellyfin access tokens at rest, encrypted with a device Android Keystore AES-256-GCM key via
 * [SecurePreferences] — no third-party crypto library (§13). Passwords are NEVER stored — only the
 * token returned after login is persisted. Backups are disabled at the manifest level and excluded by
 * data_extraction_rules.
 *
 * NOTE: this uses a new storage file from the previous EncryptedSharedPreferences-based store, so an
 * upgrade from a build that used `androidx.security:security-crypto` needs a one-time re-login — the
 * old token blob is in an incompatible format and is simply ignored, never read.
 */
class KeystoreTokenStore(context: Context) : SecureTokenStore {

    private val prefs = SecurePreferences(context, FILE_NAME, KEY_ALIAS)

    override suspend fun put(serverId: String, token: String) = withContext(Dispatchers.IO) {
        prefs.putString(key(serverId), token)
    }

    override suspend fun get(serverId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key(serverId))
    }

    override suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        prefs.remove(key(serverId))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.clear()
    }

    private fun key(serverId: String) = "token_$serverId"

    companion object {
        private const val FILE_NAME = "jellyfin_bridge_secure_tokens_v2"
        private const val KEY_ALIAS = "jellyfin_bridge_token_key"
    }
}
