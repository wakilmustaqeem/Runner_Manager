package com.fm.runnermanager.runtime

import java.io.File
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
}
