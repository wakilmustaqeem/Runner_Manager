package com.fm.runnermanager.runtime

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class RuntimeInstallCoordinatorTest {
    @Test fun refusesMissingSigningConfigurationBeforeNetworkAccess() {
        val root = Files.createTempDirectory("fm-coordinator-test-").toFile()
        try {
            val config = RuntimeInstallCoordinator.Config(
                manifestUrl = "",
                detachedManifestSignatureBase64 = "",
                trustedPublicKeyBase64 = "",
                supportedAbis = listOf("arm64-v8a"),
            )
            val result = runCatching { RuntimeInstallCoordinator.install(root, config) }
            assertFalse(result.isSuccess)
            // Fails before staging/network, so no archive or active pointer can be created.
            assertFalse(java.io.File(root, "runtime-staging").exists())
            assertFalse(java.io.File(root, "active-installed-runtime.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesUnsupportedAbiBeforeNetworkAccess() {
        val root = Files.createTempDirectory("fm-coordinator-abi-test-").toFile()
        try {
            val config = RuntimeInstallCoordinator.Config(
                manifestUrl = "https://example.invalid/manifest.json",
                detachedManifestSignatureBase64 = "signature",
                trustedPublicKeyBase64 = "key",
                supportedAbis = listOf("x86_64"),
            )
            val result = runCatching { RuntimeInstallCoordinator.install(root, config) }
            assertFalse(result.isSuccess)
            assertFalse(java.io.File(root, "runtime-staging").exists())
            assertFalse(java.io.File(root, "active-installed-runtime.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
