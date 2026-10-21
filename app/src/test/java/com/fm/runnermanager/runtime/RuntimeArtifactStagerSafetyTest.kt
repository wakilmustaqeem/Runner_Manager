package com.fm.runnermanager.runtime

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeArtifactStagerSafetyTest {
    @Test fun refusesSymlinkedStagingDirectoryBeforeNetworkAccess() {
        val root = Files.createTempDirectory("fm-runtime-stager-test-").toFile()
        val outside = Files.createTempDirectory("fm-runtime-outside-test-").toFile()
        val staging = File(root, "runtime-staging")
        try {
            Files.createSymbolicLink(staging.toPath(), outside.toPath())
            val result = runCatching {
                RuntimeArtifactStager.stage(
                    manifestUrl = "https://example.invalid/manifest.json",
                    detachedSignatureBase64 = "signature",
                    trustedPublicKeyBase64 = "key",
                    stagingDir = staging,
                    minimumAllowedVersion = 1L,
                )
            }
            assertFalse("Symlinked staging must be rejected before network access", result.isSuccess)
            assertFalse(File(outside, "runtime-staging").exists())
            assertFalse(outside.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
