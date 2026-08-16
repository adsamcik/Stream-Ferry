package com.adsamcik.streamferry.data.security

import android.content.Context
import android.content.SharedPreferences

/** A secure value could not be encrypted or durably committed to app-private storage. */
class SecureStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal interface SecureValueCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(stored: String): String?
}

/**
 * App-private string preferences whose VALUES are encrypted at rest with an Android Keystore
 * AES-256-GCM key ([KeystoreCipher]) — a direct, dependency-free replacement for the previous
 * EncryptedSharedPreferences. Keys are stored in the clear (they are non-secret field names like
 * `base_url`); only the values are encrypted. A value that can't be decrypted (corrupt, or written by
 * an older/incompatible scheme) reads back as null, so the caller treats it as absent.
 */
class SecurePreferences internal constructor(
    private val prefs: SharedPreferences,
    private val cipher: SecureValueCipher,
) {
    constructor(context: Context, fileName: String, keyAlias: String) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE),
        KeystoreCipher(keyAlias),
    )

    fun putString(key: String, value: String) {
        val encrypted = cipher.encrypt(value)
        commit("save secure app data") { putString(key, encrypted) }
    }

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return cipher.decrypt(stored)
    }

    fun remove(key: String) {
        commit("remove secure app data") { remove(key) }
    }

    fun clear() {
        commit("clear secure app data") { clear() }
    }

    private inline fun commit(action: String, mutation: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
        val committed = try {
            prefs.edit().mutation().commit()
        } catch (cause: Exception) {
            throw SecureStorageException("Couldn't $action.", cause)
        }
        if (!committed) throw SecureStorageException("Couldn't $action.")
    }
}
