package com.adsamcik.streamferry.data.security

import android.content.Context

/**
 * App-private string preferences whose VALUES are encrypted at rest with an Android Keystore
 * AES-256-GCM key ([KeystoreCipher]) — a direct, dependency-free replacement for the previous
 * EncryptedSharedPreferences. Keys are stored in the clear (they are non-secret field names like
 * `base_url`); only the values are encrypted. A value that can't be decrypted (corrupt, or written by
 * an older/incompatible scheme) reads back as null, so the caller treats it as absent.
 */
class SecurePreferences(
    context: Context,
    fileName: String,
    keyAlias: String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(keyAlias)

    fun putString(key: String, value: String) {
        val encrypted = cipher.encrypt(value) ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return cipher.decrypt(stored)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
