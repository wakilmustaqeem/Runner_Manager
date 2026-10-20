package com.fm.runnermanager.runner

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the detached ECDSA signature over the exact downloaded runtime
 * manifest bytes. A SHA-256 archive digest alone is not an origin guarantee.
 *
 * This helper deliberately fails closed when the build has no trusted key;
 * callers must not treat an unconfigured key list as successful verification.
 */
internal object RuntimeManifestSignature {
    sealed interface Result {
        data object Valid : Result
        data object Unverifiable : Result
        data class Invalid(val reason: String) : Result
    }

    fun verify(
        manifestBytes: ByteArray,
        signatureBase64: String?,
        trustedKeysBase64: String
    ): Result {
        val keys = trustedKeysBase64.split(",").map(String::trim).filter(String::isNotEmpty)
        if (keys.isEmpty()) return Result.Unverifiable
        if (signatureBase64.isNullOrBlank()) {
            return Result.Invalid("Runtime manifest signature is missing")
        }
        val signature = runCatching { Base64.getMimeDecoder().decode(signatureBase64.trim()) }
            .getOrNull() ?: return Result.Invalid("Runtime manifest signature is malformed")

        val valid = keys.any { encodedKey ->
            runCatching {
                val keyBytes = Base64.getMimeDecoder().decode(encodedKey)
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(keyBytes))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(manifestBytes)
                    verify(signature)
                }
            }.getOrDefault(false)
        }
        return if (valid) Result.Valid else Result.Invalid("Runtime manifest signature is not trusted")
    }
}
