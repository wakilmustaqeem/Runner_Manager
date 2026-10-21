package com.fm.runnermanager.runtime

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a detached ECDSA P-256/SHA-256 signature over the exact manifest bytes.
 *
 * The caller must invoke this before decoding/parsing the manifest. The configured
 * key is Base64-encoded X.509 SubjectPublicKeyInfo DER; the signature is Base64-encoded
 * ASN.1 DER ECDSA. No network, storage, or JSON parsing occurs here.
 */
object RuntimeManifestSignature {
    fun verify(
        exactManifestBytes: ByteArray,
        detachedSignatureBase64: String,
        trustedPublicKeyBase64: String,
    ): Boolean {
        if (exactManifestBytes.isEmpty()) return false
        if (detachedSignatureBase64.isBlank() || trustedPublicKeyBase64.isBlank()) return false

        return runCatching {
            val decoder = Base64.getDecoder()
            val keyBytes = decoder.decode(trustedPublicKeyBase64.trim())
            val signatureBytes = decoder.decode(detachedSignatureBase64.trim())
            if (keyBytes.isEmpty() || signatureBytes.isEmpty()) return false

            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes)) as? ECPublicKey ?: return false
            // Require a 256-bit prime-field curve, rather than accepting arbitrary EC keys.
            if (publicKey.params.curve.field.fieldSize != 256 ||
                publicKey.params.order.bitLength() != 256
            ) return false

            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(exactManifestBytes)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }
}
