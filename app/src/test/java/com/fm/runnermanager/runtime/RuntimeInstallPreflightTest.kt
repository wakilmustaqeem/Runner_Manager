package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeInstallPreflightTest {
    private val validSignature = RuntimeManifestSignatureVerifier.Result.Valid
    private val validFields = RuntimeManifestFieldsValidator.Result.Valid
    private val validDigest = RuntimeArchiveDigestVerifier.Result.Match

    @Test
    fun allowsOnlyWhenAllThreeChecksPass() {
        assertEquals(
            RuntimeInstallPreflight.Decision.AllowVerifiedArchive,
            RuntimeInstallPreflight.evaluate(validSignature, validFields, validDigest)
        )
    }

    @Test
    fun blocksWhenNoTrustedSigningKeyIsConfigured() {
        val result = RuntimeInstallPreflight.evaluate(
            RuntimeManifestSignatureVerifier.Result.Unverifiable,
            validFields,
            validDigest
        )
        assertEquals(true, result is RuntimeInstallPreflight.Decision.Blocked)
    }

    @Test
    fun blocksInvalidManifestFieldsEvenWhenSignatureIsValid() {
        val result = RuntimeInstallPreflight.evaluate(
            validSignature,
            RuntimeManifestFieldsValidator.Result.Invalid("bad fields"),
            validDigest
        )
        assertEquals(true, result is RuntimeInstallPreflight.Decision.Blocked)
    }

    @Test
    fun blocksArchiveDigestMismatchEvenWhenManifestIsValid() {
        val result = RuntimeInstallPreflight.evaluate(
            validSignature,
            validFields,
            RuntimeArchiveDigestVerifier.Result.Mismatch
        )
        assertEquals(true, result is RuntimeInstallPreflight.Decision.Blocked)
    }

    @Test
    fun blocksArchiveReadFailure() {
        val result = RuntimeInstallPreflight.evaluate(
            validSignature,
            validFields,
            RuntimeArchiveDigestVerifier.Result.ReadFailure("read failed")
        )
        assertEquals(true, result is RuntimeInstallPreflight.Decision.Blocked)
    }

    @Test
    fun blocksMalformedExpectedDigest() {
        val result = RuntimeInstallPreflight.evaluate(
            validSignature,
            validFields,
            RuntimeArchiveDigestVerifier.Result.InvalidExpectedDigest
        )
        assertEquals(true, result is RuntimeInstallPreflight.Decision.Blocked)
    }
}
