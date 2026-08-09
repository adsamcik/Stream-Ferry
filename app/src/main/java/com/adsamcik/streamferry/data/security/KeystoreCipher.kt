package com.adsamcik.streamferry.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed directly by the Android Keystore — no third-party crypto library
 * (§13). The key is non-exportable and lives in the device Keystore (hardware-backed where available),
 * keyed by [alias]. [encrypt] returns Base64(iv || ciphertext+tag) and throws [SecureStorageException]
 * if a new secret cannot be protected; silently dropping a credential would make a successful login
 * disappear on restart. [decrypt] still returns null for corrupt/obsolete stored data so callers can
 * safely degrade to a re-login.
 *
 * GCM with a Keystore key uses a fresh random IV per [encrypt] call (the platform forbids a
 * caller-supplied IV here), so encrypting the same plaintext twice yields different ciphertexts.
 */
internal class KeystoreCipher(private val alias: String) : SecureValueCipher {

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt [plaintext], preserving the cause when Android Keystore cannot protect the value. */
    override fun encrypt(plaintext: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
        Base64.encodeToString(blob, Base64.NO_WRAP)
    }.getOrElse { cause ->
        throw SecureStorageException("Couldn't encrypt secure app data.", cause)
    }

    /** Decrypt a value produced by [encrypt]; returns null on any failure. */
    override fun decrypt(stored: String): String? = runCatching {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size <= GCM_IV_BYTES) return null
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
