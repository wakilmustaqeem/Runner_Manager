/*
 * FM Runner Manager runtime manifest signature gate.
 *
 * This implementation follows the documented verification model used by
 * DroidRunner (https://github.com/m96-chan/DroidRunner): ECDSA P-256 with
 * SHA-256 over the exact served manifest bytes and X.509/base64 public keys.
 * It is independently written, not copied from upstream.
 *
 * IMPORTANT: a caller must supply keys pinned by the app build/release process.
 * An empty key set returns Unverifiable and MUST block runtime installation.
 * A signature alone is not proof of a trusted origin unless its key is trusted.
 */
package com.fm.runnermanager.runtime

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object RuntimeManifestSignatureVerifier {
    sealed interface Result {
        data object Valid : Result
        data object Unverifiable : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * @param manifestBytes exact bytes received from HTTPS; do not parse and
     *   serialize again before verification.
     * @param signatureBase64 detached .sig file content.
     * @param trustedKeysBase64 comma-separated X.509/base64 public keys pinned
     *   by the build. Never accept keys supplied by the manifest or network.
     */
    fun verify(
        manifestBytes: ByteArray,
        signatureBase64: String?,
        trustedKeysBase64: String
    ): Result {
        val keys = trustedKeysBase64.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (keys.isEmpty()) return Result.Unverifiable
        if (signatureBase64.isNullOrBlank()) {
            return Result.Invalid("Required manifest signature is missing")
        }

        val signatureBytes = runCatching {
            Base64.getMimeDecoder().decode(signatureBase64.trim())
        }.getOrElse {
            return Result.Invalid("Manifest signature is not valid base64")
        }

        val verified = keys.any { encodedKey ->
            runCatching {
                val keyBytes = Base64.getMimeDecoder().decode(encodedKey)
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(keyBytes)) as ECPublicKey
                // Only trust P-256 keys for this protocol.
                if (publicKey.params.curve.field.fieldSize != 256) return@runCatching false
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(manifestBytes)
                    verify(signatureBytes)
                }
            }.getOrDefault(false)
        }

        return if (verified) Result.Valid
        else Result.Invalid("Signature does not match any pinned P-256 key")
    }
}
