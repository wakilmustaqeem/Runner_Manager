/*
 * Pinned runtime-manifest trust entry point.
 *
 * The public key is shipped as an app resource; a manifest must never be
 * allowed to supply or replace its own trust key.
 */
package com.fm.runnermanager.runtime

import android.content.Context
import com.fm.runnermanager.R
import java.io.IOException
import java.util.Base64

object PinnedRuntimeManifestVerifier {
    /**
     * Verify exact manifest bytes against the public key pinned in this APK.
     * Returns Unverifiable on resource/key loading failures; callers must fail
     * closed and must not install or activate a runtime in that case.
     *
     * This is only the signature check. Callers must separately validate
     * manifest fields, archive digest, archive paths, and runtime layout.
     */
    fun verify(
        context: Context,
        manifestBytes: ByteArray,
        signatureBase64: String?
    ): RuntimeManifestSignatureVerifier.Result {
        val encodedKey = try {
            context.resources.openRawResource(R.raw.runtime_signing_public)
                .bufferedReader(Charsets.US_ASCII).use { reader ->
                    val pem = reader.readText()
                    val body = pem
                        .lineSequence()
                        .filterNot { it.startsWith("-----BEGIN ") || it.startsWith("-----END ") }
                        .joinToString("")
                        .filterNot(Char::isWhitespace)
                    if (body.isEmpty()) throw IOException("Pinned public key is empty")
                    // Validate that the PEM body is decodable before passing it on.
                    val der = Base64.getDecoder().decode(body)
                    Base64.getEncoder().encodeToString(der)
                }
        } catch (_: Exception) {
            return RuntimeManifestSignatureVerifier.Result.Unverifiable
        }

        return RuntimeManifestSignatureVerifier.verify(
            manifestBytes = manifestBytes,
            signatureBase64 = signatureBase64,
            trustedKeysBase64 = encodedKey
        )
    }
}
