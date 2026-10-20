package com.fm.runnermanager.security

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
 * Small Android Keystore-backed vault for GitHub access tokens.
 *
 * Tokens are encrypted with AES-GCM; only ciphertext and the IV are written to
 * app-private preferences. This class never logs token material. It does not make
 * a GitHub token permanent: revoked/expired tokens still require reauthorization.
 */
class CredentialVault(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("encrypted_credential_vault", Context.MODE_PRIVATE)

    @Synchronized
    fun save(accountSlot: Int, token: String) {
        require(accountSlot > 0) { "Invalid account slot" }
        require(token.isNotBlank()) { "Token is required" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + ciphertext.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(ciphertext, 0, packed, cipher.iv.size, ciphertext.size)
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        check(preferences.edit().putString(slotKey(accountSlot), encoded).commit()) {
            "Unable to persist encrypted credential"
        }
    }

    @Synchronized
    fun load(accountSlot: Int): String? {
        require(accountSlot > 0) { "Invalid account slot" }
        val encoded = preferences.getString(slotKey(accountSlot), null) ?: return null
        val packed = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            clear(accountSlot)
            return null
        }
        if (packed.size <= IV_LENGTH_BYTES) {
            clear(accountSlot)
            return null
        }
        val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun clear(accountSlot: Int) {
        require(accountSlot > 0) { "Invalid account slot" }
        check(preferences.edit().remove(slotKey(accountSlot)).commit()) {
            "Unable to clear encrypted credential"
        }
    }

    @Synchronized
    fun clearAll() {
        check(preferences.edit().clear().commit()) { "Unable to clear encrypted credentials" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun slotKey(accountSlot: Int) = "account_slot_$accountSlot"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fm_runner_manager_github_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
