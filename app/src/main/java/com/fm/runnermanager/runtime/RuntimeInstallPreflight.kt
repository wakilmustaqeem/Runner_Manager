/*
 * Combined fail-closed preflight for a future runtime installer.
 *
 * This policy only decides whether verification inputs are sufficient. It
 * performs no network, filesystem, extraction, installation, or process action.
 * Callers must invoke it after verifying the exact manifest bytes/signature,
 * validating decoded fields, and hashing the downloaded archive stream.
 */
package com.fm.runnermanager.runtime

object RuntimeInstallPreflight {
    sealed interface Decision {
        data object AllowVerifiedArchive : Decision
        data class Blocked(val reason: String) : Decision
    }

    fun evaluate(
        signature: RuntimeManifestSignatureVerifier.Result,
        fields: RuntimeManifestFieldsValidator.Result,
        archiveDigest: RuntimeArchiveDigestVerifier.Result
    ): Decision {
        if (signature !is RuntimeManifestSignatureVerifier.Result.Valid) {
            val reason = when (signature) {
                RuntimeManifestSignatureVerifier.Result.Unverifiable ->
                    "No trusted runtime signing key is configured"
                is RuntimeManifestSignatureVerifier.Result.Invalid -> signature.reason
                RuntimeManifestSignatureVerifier.Result.Valid -> error("unreachable")
            }
            return Decision.Blocked("Manifest signature verification failed: $reason")
        }

        if (fields !is RuntimeManifestFieldsValidator.Result.Valid) {
            val reason = (fields as RuntimeManifestFieldsValidator.Result.Invalid).reason
            return Decision.Blocked("Manifest field validation failed: $reason")
        }

        if (archiveDigest !is RuntimeArchiveDigestVerifier.Result.Match) {
            val reason = when (archiveDigest) {
                RuntimeArchiveDigestVerifier.Result.InvalidExpectedDigest ->
                    "Expected archive SHA-256 is malformed"
                RuntimeArchiveDigestVerifier.Result.Mismatch ->
                    "Downloaded archive SHA-256 does not match the signed manifest"
                is RuntimeArchiveDigestVerifier.Result.ReadFailure ->
                    "Downloaded archive could not be read completely"
                RuntimeArchiveDigestVerifier.Result.Match -> error("unreachable")
            }
            return Decision.Blocked("Archive digest verification failed: $reason")
        }

        return Decision.AllowVerifiedArchive
    }
}
