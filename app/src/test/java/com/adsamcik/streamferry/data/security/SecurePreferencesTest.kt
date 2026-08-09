package com.adsamcik.streamferry.data.security

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurePreferencesTest {

    @Test
    fun encryptionFailureIsReturnedToTheCaller() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val securePreferences = SecurePreferences(prefs, ThrowingCipher)

        val failure = assertFailsWith<SecureStorageException> {
            securePreferences.putString("token", "secret")
        }

        assertEquals("encryption failed", failure.message)
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun failedPreferenceCommitIsReturnedToTheCaller() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putString("token", "encrypted:secret") } returns editor
        every { editor.commit() } returns false
        val securePreferences = SecurePreferences(prefs, PrefixCipher)

        assertFailsWith<SecureStorageException> {
            securePreferences.putString("token", "secret")
        }
    }

    @Test
    fun successfulPreferenceCommitStoresTheEncryptedValueSynchronously() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putString("token", "encrypted:secret") } returns editor
        every { editor.commit() } returns true
        val securePreferences = SecurePreferences(prefs, PrefixCipher)

        securePreferences.putString("token", "secret")

        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun failedRemovalIsReturnedToTheCaller() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.remove("token") } returns editor
        every { editor.commit() } throws IllegalStateException("disk unavailable")
        val securePreferences = SecurePreferences(prefs, PrefixCipher)

        val failure = assertFailsWith<SecureStorageException> {
            securePreferences.remove("token")
        }

        assertEquals("disk unavailable", failure.cause?.message)
    }

    private object PrefixCipher : SecureValueCipher {
        override fun encrypt(plaintext: String): String = "encrypted:$plaintext"
        override fun decrypt(stored: String): String = stored.removePrefix("encrypted:")
    }

    private object ThrowingCipher : SecureValueCipher {
        override fun encrypt(plaintext: String): String = throw SecureStorageException("encryption failed")
        override fun decrypt(stored: String): String? = null
    }
}
