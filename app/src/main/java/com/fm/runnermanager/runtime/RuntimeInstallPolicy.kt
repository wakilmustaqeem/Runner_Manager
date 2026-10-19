/*
 * Fail-closed decision helper for future runtime installation integration.
 *
 * This is not an installer and performs no filesystem/network/process actions.
 * Installation may proceed only when both signature verification and decoded
 * manifest-field validation have succeeded. Callers must still verify the
 * downloaded archive SHA-256 against the signed manifest before extraction.
 */
package com.fm.runnermanager.runtime

object RuntimeInstallPolicy {
    sealed interface Decision {
        data object AllowVerifiedManifest : Decision
        data class Blocked(val reason: String) : Decision
    }

    fun evaluate(
        signatureResult: RuntimeManifestSignatureVerifier.Result,
        fieldsResult: RuntimeManifestFieldsValidator.Result
    ): Decision {
        if (signatureResult !is RuntimeManifestSignatureVerifier.Result.Valid) {
            val reason = when (signatureResult) {
                RuntimeManifestSignatureVerifier.Result.Unverifiable ->
                    "No trusted runtime signing key is configured"
                is RuntimeManifestSignatureVerifier.Result.Invalid ->
                    signatureResult.reason
                RuntimeManifestSignatureVerifier.Result.Valid -> error("unreachable")
            }
            return Decision.Blocked("Signature verification failed: $reason")
        }

        if (fieldsResult !is RuntimeManifestFieldsValidator.Result.Valid) {
            val reason = (fieldsResult as RuntimeManifestFieldsValidator.Result.Invalid).reason
            return Decision.Blocked("Manifest field validation failed: $reason")
        }

        return Decision.AllowVerifiedManifest
    }
}
