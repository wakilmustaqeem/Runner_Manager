package com.fm.runnermanager.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Durable pointer to an installed runtime directory.
 *
 * This is metadata-only: it does not launch PRoot or start a GitHub Actions runner.
 * The pointer is written only after the installer has verified the archive and
 * published a complete runtime directory containing a real rootfs directory.
 */
object InstalledRuntimeStore {
    data class ActiveInstalledRuntime(
        val version: Long,
        val directoryName: String,
        val sha256: String,
    ) {
        fun directory(appPrivateRoot: File): File = File(appPrivateRoot, directoryName)
        fun rootfs(appPrivateRoot: File): File = File(directory(appPrivateRoot), "rootfs")
    }

    // TODO: move to BuildConfig in a later commit so it can be tuned per release.
    private const val MINIMUM_ALLOWED_VERSION = 1L

    private fun pointerTxn(root: File) = RuntimePointerTransaction(
        root = root,
        minimumAllowedVersion = MINIMUM_ALLOWED_VERSION,
        verifyDirectory = { p ->
            try {
                validateInstalledDirectory(root, p.directoryName)
                true
            } catch (_: Exception) {
                false
            }
        },
    )

    /**
     * Installs and selects a verified runtime. If pointer publication fails, the
     * prior pointer is preserved; the newly installed directory may remain as an
     * inactive recoverable artifact and is never executed by this class.
     */
    @Synchronized
    fun installAndActivate(
        appPrivateRoot: File,
        artifact: RuntimeArtifactStager.StagedArtifact,
        extractor: RuntimeTarGzExtractor = RuntimeTarGzExtractor(),
    ): ActiveInstalledRuntime {
        if (!appPrivateRoot.exists() && !appPrivateRoot.mkdirs()) {
            throw IOException("Unable to create app-private runtime root")
        }
        if (!appPrivateRoot.isDirectory || Files.isSymbolicLink(appPrivateRoot.toPath())) {
            throw SecurityException("Runtime root must be a real app-private directory")
        }
        val root = appPrivateRoot.canonicalFile
        val stagedArchive = artifact.file.canonicalFile
        val rootPrefix = root.path + File.separator
        if (!stagedArchive.path.startsWith(rootPrefix) || !stagedArchive.isFile) {
            throw SecurityException("Staged archive must be a regular file inside app-private storage")
        }
        val digest = artifact.sha256.lowercase()
        if (!digest.matches(Regex("^[0-9a-f]{64}$"))) {
            throw SecurityException("Invalid staged runtime digest")
        }
        val directoryName = "runtime-${artifact.version}-${digest.take(12)}"
        if (File(directoryName).name != directoryName || directoryName.contains("..")) {
            throw SecurityException("Invalid installed runtime directory name")
        }

        val installedDir = File(root, directoryName)
        if (installedDir.exists() || Files.isSymbolicLink(installedDir.toPath())) {
            // Do not trust a pre-existing directory merely because its name embeds a digest.
            throw IOException("Installed runtime destination already exists; refusing unverified reuse")
        }
        RuntimeArchiveInstaller.install(artifact, installedDir, extractor)
        validateInstalledDirectory(root, directoryName)
        val active = ActiveInstalledRuntime(artifact.version, directoryName, digest)
        pointerTxn(root).writeActive(
            version = artifact.version,
            directoryName = directoryName,
            sha256 = digest,
        )
        return active
    }

    fun readActive(appPrivateRoot: File): ActiveInstalledRuntime? {
        if (!appPrivateRoot.isDirectory ||
            Files.isSymbolicLink(appPrivateRoot.toPath())
        ) {
            return null
        }
        val root = runCatching {
            appPrivateRoot.canonicalFile
        }.getOrNull() ?: return null

        return runCatching {
            val f = File(root, RuntimePointerTransaction.POINTER_NAME)
            if (!f.isFile || Files.isSymbolicLink(f.toPath())) return null
            val p = RuntimePointer.parse(f.readBytes())
            validateInstalledDirectory(root, p.directoryName)
            ActiveInstalledRuntime(p.version, p.directoryName, p.sha256)
        }.getOrNull()
    }

    /**
     * Strict read for installation/rollback decisions. A present but invalid active
     * pointer must never silently reset the anti-rollback floor to zero. If a process
     * died between pointer renames, restore a validated previous pointer; on a first
     * install, a validated pending pointer can be completed when no backup exists.
     */
    @Synchronized
    fun readActiveForInstall(appPrivateRoot: File): ActiveInstalledRuntime? {
        if (!appPrivateRoot.isDirectory || Files.isSymbolicLink(appPrivateRoot.toPath())) {
            throw SecurityException("Runtime root must be a real app-private directory")
        }
        val root = appPrivateRoot.canonicalFile
        val anyArtefact = listOf(
            File(root, RuntimePointerTransaction.POINTER_NAME),
            File(root, RuntimePointerTransaction.PENDING_NAME),
            File(root, RuntimePointerTransaction.PREVIOUS_NAME),
            File(root, RuntimePointerTransaction.COMMIT_NAME),
        ).any { it.exists() || Files.isSymbolicLink(it.toPath()) }
        if (!anyArtefact) return null

        return when (val r = pointerTxn(root).recover()) {
            is RuntimePointerTransaction.RecoveryResult.Active ->
                ActiveInstalledRuntime(
                    r.pointer.version,
                    r.pointer.directoryName,
                    r.pointer.sha256,
                )
            is RuntimePointerTransaction.RecoveryResult.SafeFailure ->
                throw SecurityException(
                    "Runtime recovery ended in SafeFailure; " +
                        "refusing to reset rollback floor"
                )
        }
    }

    /**
     * Deterministic startup recovery. Call before any runtime use.
     * Returns null on SafeFailure or first install and never throws.
     */
    fun recoverOnStartup(appPrivateRoot: File): ActiveInstalledRuntime? {
        if (!appPrivateRoot.isDirectory ||
            Files.isSymbolicLink(appPrivateRoot.toPath())
        ) {
            return null
        }
        val root = runCatching { appPrivateRoot.canonicalFile }.getOrNull()
            ?: return null
        return try {
            when (val r = pointerTxn(root).recover()) {
                is RuntimePointerTransaction.RecoveryResult.Active ->
                    ActiveInstalledRuntime(
                        r.pointer.version,
                        r.pointer.directoryName,
                        r.pointer.sha256,
                    )
                is RuntimePointerTransaction.RecoveryResult.SafeFailure -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validateInstalledDirectory(root: File, name: String) {
        val candidate = File(root, name)
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        if (canonicalCandidate.parentFile != canonicalRoot ||
            Files.isSymbolicLink(candidate.toPath()) ||
            !Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw SecurityException("Installed runtime must be a direct, real child of app-private storage")
        }
        val rootfs = File(candidate, "rootfs")
        if (Files.isSymbolicLink(rootfs.toPath()) ||
            !Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw SecurityException("Installed runtime has no real rootfs directory")
        }
    }

}
