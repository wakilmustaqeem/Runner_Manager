package com.fm.runnermanager.runtime

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePreflightTest {
    @Test fun refusesNonHttpsAndMissingTrustedKey() {
        val result = RuntimePreflight.inspect(
            filesDir = File(System.getProperty("java.io.tmpdir")),
            archiveBytes = 1L,
            manifestUrl = "http://example.invalid/manifest.json",
            trustedSigningKeyConfigured = false,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertFalse(result.ready)
        assertTrue(result.blockers.any { it.contains("HTTPS") })
        assertTrue(result.blockers.any { it.contains("FM-owned trusted runtime signing key") })
    }

    @Test fun acceptsOnlyMatchingSha256() {
        val file = File.createTempFile("fm-runtime-preflight", ".bin")
        try {
            file.writeText("FM Runner Manager")
            val digest = RuntimePreflight.sha256(file)
            assertTrue(RuntimePreflight.matchesSha256(file, digest))
            assertFalse(RuntimePreflight.matchesSha256(file, "0".repeat(64)))
            assertFalse(RuntimePreflight.matchesSha256(file, "not-a-sha256"))
        } finally {
            file.delete()
        }
    }

    @Test fun refusesUnsupportedArchitecture() {
        val result = RuntimePreflight.inspect(
            filesDir = File(System.getProperty("java.io.tmpdir")),
            archiveBytes = 1L,
            manifestUrl = "https://example.invalid/manifest.json",
            trustedSigningKeyConfigured = true,
            supportedAbis = listOf("x86_64"),
        )
        assertFalse(result.ready)
        assertTrue(result.blockers.any { it.contains("arm64-v8a") })
    }

    @Test fun verifiesExactManifestBytesWithEcdsaP256() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val manifest = """{"version":1,"archive":"runtime.tar.zst"}""".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(manifest)
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertTrue(RuntimeManifestSignature.verify(manifest, signature, publicKey))
        assertFalse(RuntimeManifestSignature.verify(
            """{"version":2,"archive":"runtime.tar.zst"}""".toByteArray(Charsets.UTF_8),
            signature,
            publicKey,
        ))
        assertFalse(RuntimeManifestSignature.verify(manifest, "not-base64", publicKey))
        assertFalse(RuntimeManifestSignature.verify(manifest, signature, ""))
    }

    @Test fun rejectsArchiveTraversalAndAbsolutePaths() {
        assertTrue(RuntimeArchivePathPolicy.isSafeEntryPath("rootfs/usr/bin/runner"))
        assertFalse(RuntimeArchivePathPolicy.isSafeEntryPath("../outside"))
        assertFalse(RuntimeArchivePathPolicy.isSafeEntryPath("rootfs/../../outside"))
        assertFalse(RuntimeArchivePathPolicy.isSafeEntryPath("/etc/passwd"))
        assertFalse(RuntimeArchivePathPolicy.isSafeEntryPath("C:/windows/system.ini"))
        assertFalse(RuntimeArchivePathPolicy.isSafeEntryPath("rootfs\\..\\outside"))
    }

    @Test fun rejectsSymlinkTargetsThatEscapeArchiveRoot() {
        assertTrue(RuntimeArchivePathPolicy.isSafeSymlinkTarget("rootfs/bin/link", "../lib/runner"))
        assertFalse(RuntimeArchivePathPolicy.isSafeSymlinkTarget("rootfs/link", "../../outside"))
        assertFalse(RuntimeArchivePathPolicy.isSafeSymlinkTarget("rootfs/bin/link", "/etc/passwd"))
    }
}
