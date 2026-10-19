package com.fm.runnermanager.adapters

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted-at-rest vault for rotating GitHub App user refresh tokens.
 * AES keys are held by Android Keystore; token values are never stored as plaintext.
 * Do not log values returned by this class.
 */
class GitHubAppTokenVault(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("github_app_token_vault", Context.MODE_PRIVATE)

    @Synchronized
    fun putRefreshToken(slot: Int, token: String) {
        require(slot > 0) { "Invalid account slot" }
        require(token.isNotBlank()) { "Refresh token is empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(key(slot), stored).commit()) {
            "Could not persist encrypted refresh token"
        }
    }

    @Synchronized
    fun getRefreshToken(slot: Int): String? {
        val stored = preferences.getString(key(slot), null) ?: return null
        return try {
            val split = stored.split(":", limit = 2)
            require(split.size == 2) { "Invalid encrypted token record" }
            val iv = Base64.decode(split[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(split[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Fail closed: unreadable ciphertext must not be treated as an authenticated session.
            null
        }
    }

    @Synchronized
    fun remove(slot: Int) {
        preferences.edit().remove(key(slot)).commit()
    }

    private fun key(slot: Int) = "refresh_token_slot_$slot"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fm_runner_github_refresh_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
