package com.fm.runnermanager.runtime

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeManifestSignatureVerifierTest {
    private lateinit var keyPair: KeyPair
    private val manifest = """{"version":"test-runtime","url":"https://example.invalid/runtime.tar.gz","sha256":"0123456789abcdef"}"""
        .toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    @Test
    fun acceptsSignatureFromPinnedP256Key() {
        val result = RuntimeManifestSignatureVerifier.verify(
            manifest, sign(manifest, keyPair), encode(keyPair.public.encoded)
        )
        assertEquals(RuntimeManifestSignatureVerifier.Result.Valid, result)
    }

    @Test
    fun rejectsAlteredManifestBytes() {
        val altered = manifest + byteArrayOf(' '.code.toByte())
        val result = RuntimeManifestSignatureVerifier.verify(
            altered, sign(manifest, keyPair), encode(keyPair.public.encoded)
        )
        assertTrue(result is RuntimeManifestSignatureVerifier.Result.Invalid)
    }

    @Test
    fun rejectsMissingSignatureWhenKeyIsPinned() {
        val result = RuntimeManifestSignatureVerifier.verify(
            manifest, null, encode(keyPair.public.encoded)
        )
        assertTrue(result is RuntimeManifestSignatureVerifier.Result.Invalid)
    }

    @Test
    fun rejectsMalformedSignature() {
        val result = RuntimeManifestSignatureVerifier.verify(
            manifest, "%%%not-base64%%%", encode(keyPair.public.encoded)
        )
        assertTrue(result is RuntimeManifestSignatureVerifier.Result.Invalid)
    }

    @Test
    fun rejectsSignatureFromUntrustedKey() {
        val otherKey = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val result = RuntimeManifestSignatureVerifier.verify(
            manifest, sign(manifest, keyPair), encode(otherKey.public.encoded)
        )
        assertTrue(result is RuntimeManifestSignatureVerifier.Result.Invalid)
    }

    @Test
    fun reportsUnverifiableWhenNoTrustedKeyIsConfigured() {
        val result = RuntimeManifestSignatureVerifier.verify(manifest, null, "")
        assertEquals(RuntimeManifestSignatureVerifier.Result.Unverifiable, result)
    }

    private fun sign(bytes: ByteArray, pair: KeyPair): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(bytes)
                sign()
            }
        )

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
