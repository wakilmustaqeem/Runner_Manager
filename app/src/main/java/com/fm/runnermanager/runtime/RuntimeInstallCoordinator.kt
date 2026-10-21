package com.fm.runnermanager.runtime

import java.io.File
import java.io.IOException

/**
 * One fail-closed orchestration path for signed runtime staging, installation,
 * and installed-pointer publication. This does not launch PRoot or register a runner.
 */
object RuntimeInstallCoordinator {
    data class Config(
        val manifestUrl: String,
        val detachedManifestSignatureBase64: String,
        val trustedPublicKeyBase64: String,
        val minimumAllowedVersion: Long = 1L,
        val supportedAbis: List<String>,
    )

    data class Result(
        val version: Long,
        val installedDirectory: File,
        val rootfsDirectory: File,
        val sha256: String,
    )

    fun install(
        appPrivateRoot: File,
        config: Config,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        extractor: RuntimeTarGzExtractor = RuntimeTarGzExtractor(),
    ): Result {
        if (!appPrivateRoot.exists() && !appPrivateRoot.mkdirs()) {
            throw IOException("Unable to create app-private runtime root")
        }
        if (!appPrivateRoot.isDirectory || java.nio.file.Files.isSymbolicLink(appPrivateRoot.toPath())) {
            throw SecurityException("Runtime storage must be a real app-private directory")
        }
        if (config.supportedAbis.firstOrNull() != "arm64-v8a") {
            throw SecurityException("Only arm64-v8a runtime packaging is currently supported")
        }
        if (!config.manifestUrl.startsWith("https://") ||
            config.manifestUrl.isBlank() ||
            config.trustedPublicKeyBase64.isBlank() ||
            config.detachedManifestSignatureBase64.isBlank()
        ) {
            throw SecurityException("FM-owned signed runtime manifest configuration is incomplete")
        }
        if (config.minimumAllowedVersion < 0L) {
            throw IllegalArgumentException("Invalid runtime rollback floor")
        }

        val root = appPrivateRoot.canonicalFile
        val installedVersion = InstalledRuntimeStore.readActiveForInstall(root)?.version ?: 0L
        val rollbackFloor = maxOf(config.minimumAllowedVersion, installedVersion)
        val staging = File(root, "runtime-staging")
        val artifact = RuntimeArtifactStager.stage(
            manifestUrl = config.manifestUrl,
            detachedSignatureBase64 = config.detachedManifestSignatureBase64,
            trustedPublicKeyBase64 = config.trustedPublicKeyBase64,
            stagingDir = staging,
            minimumAllowedVersion = rollbackFloor,
            nowEpochSeconds = nowEpochSeconds,
        )

        // Re-run the device/storage gate with the signed manifest's actual archive size.
        val preflight = RuntimePreflight.inspect(
            filesDir = root,
            archiveBytes = artifact.archiveBytes,
            manifestUrl = config.manifestUrl,
            trustedSigningKeyConfigured = config.trustedPublicKeyBase64.isNotBlank(),
            supportedAbis = config.supportedAbis,
        )
        if (!preflight.ready) {
            throw SecurityException("Runtime preflight blocked installation: ${preflight.blockers.joinToString("; ")}")
        }

        val active = InstalledRuntimeStore.installAndActivate(root, artifact, extractor)
        return Result(
            version = active.version,
            installedDirectory = active.directory(root),
            rootfsDirectory = active.rootfs(root),
            sha256 = active.sha256,
        )
    }
}
