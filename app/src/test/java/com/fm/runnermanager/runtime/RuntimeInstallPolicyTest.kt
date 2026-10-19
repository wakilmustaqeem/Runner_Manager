package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallPolicyTest {
    @Test
    fun allowsOnlyWhenSignatureAndFieldsAreValid() {
        assertEquals(
            RuntimeInstallPolicy.Decision.AllowVerifiedManifest,
            RuntimeInstallPolicy.evaluate(
                RuntimeManifestSignatureVerifier.Result.Valid,
                RuntimeManifestFieldsValidator.Result.Valid
            )
        )
    }

    @Test
    fun blocksWhenSignatureIsUnverifiable() {
        val result = RuntimeInstallPolicy.evaluate(
            RuntimeManifestSignatureVerifier.Result.Unverifiable,
            RuntimeManifestFieldsValidator.Result.Valid
        )
        assertTrue(result is RuntimeInstallPolicy.Decision.Blocked)
    }

    @Test
    fun blocksWhenSignatureIsInvalid() {
        val result = RuntimeInstallPolicy.evaluate(
            RuntimeManifestSignatureVerifier.Result.Invalid("bad signature"),
            RuntimeManifestFieldsValidator.Result.Valid
        )
        assertTrue(result is RuntimeInstallPolicy.Decision.Blocked)
    }

    @Test
    fun blocksWhenManifestFieldsAreInvalidEvenWithValidSignature() {
        val result = RuntimeInstallPolicy.evaluate(
            RuntimeManifestSignatureVerifier.Result.Valid,
            RuntimeManifestFieldsValidator.Result.Invalid("bad URL")
        )
        assertTrue(result is RuntimeInstallPolicy.Decision.Blocked)
    }

    @Test
    fun blocksWhenBothChecksFail() {
        val result = RuntimeInstallPolicy.evaluate(
            RuntimeManifestSignatureVerifier.Result.Unverifiable,
            RuntimeManifestFieldsValidator.Result.Invalid("bad digest")
        )
        assertTrue(result is RuntimeInstallPolicy.Decision.Blocked)
    }
}
