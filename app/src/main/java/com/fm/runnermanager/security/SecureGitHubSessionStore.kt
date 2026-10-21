package com.fm.runnermanager.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-account encrypted GitHub session storage.
 *
 * Only AES-GCM ciphertext is written to SharedPreferences. Encryption keys stay
 * in Android Keystore and each account slot has an independent key and record.
 * This protects tokens at rest; it does not extend GitHub token lifetime.
 */
class SecureGitHubSessionStore(context: Context) {
    data class Session(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtEpochSeconds: Long?
    )

    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences("encrypted_github_sessions_v1", Context.MODE_PRIVATE)
    }

    fun save(slot: Int, session: Session) {
        require(slot > 0) { "Account slot must be positive" }
        require(session.accessToken.isNotBlank()) { "Access token is required" }
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken ?: JSONObject.NULL)
            .put("expires_at_epoch_seconds", session.expiresAtEpochSeconds ?: JSONObject.NULL)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(slot))
        val encrypted = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString(recordKey(slot), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted GitHub session"
        }
    }

    fun load(slot: Int): Session? {
        require(slot > 0) { "Account slot must be positive" }
        val encoded = preferences.getString(recordKey(slot), null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12) { "Encrypted session is truncated" }
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyFor(slot), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
            Session(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" },
                expiresAtEpochSeconds = if (json.isNull("expires_at_epoch_seconds")) null
                    else json.optLong("expires_at_epoch_seconds").takeIf { it > 0L }
            )
        } catch (_: Exception) {
            clear(slot)
            null
        }
    }

    fun clear(slot: Int) {
        if (slot <= 0) return
        preferences.edit().remove(recordKey(slot)).commit()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias(slot))
        }
    }

    private fun keyFor(slot: Int): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyAlias = alias(slot)
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun alias(slot: Int) = "fm_github_session_slot_$slot"
    private fun recordKey(slot: Int) = "slot_$slot"
}
