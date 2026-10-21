package com.fm.runnermanager.runtime

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

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

    private const val POINTER_NAME = "active-installed-runtime.json"
    private const val PENDING_NAME = "active-installed-runtime.json.pending"
    private const val BACKUP_NAME = "active-installed-runtime.json.previous"

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
        writePointerTransactionally(root, active)
        return active
    }

    fun readActive(appPrivateRoot: File): ActiveInstalledRuntime? {
        if (!appPrivateRoot.isDirectory || Files.isSymbolicLink(appPrivateRoot.toPath())) return null
        val root = runCatching { appPrivateRoot.canonicalFile }.getOrNull() ?: return null
        return runCatching { readPointerFile(root, File(root, POINTER_NAME)) }.getOrNull()
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
        val pointer = File(root, POINTER_NAME)
        val backup = File(root, BACKUP_NAME)
        val pending = File(root, PENDING_NAME)

        if (pointer.exists() || Files.isSymbolicLink(pointer.toPath())) {
            return readPointerFile(root, pointer)
                ?: throw SecurityException("Active runtime pointer exists but is invalid; refusing rollback-floor reset")
        }

        if (backup.exists() || Files.isSymbolicLink(backup.toPath())) {
            val previous = readPointerFile(root, backup)
                ?: throw SecurityException("Previous runtime pointer is invalid; refusing installation")
            if (!backup.renameTo(pointer)) throw IOException("Unable to recover previous runtime pointer")
            return previous
        }

        if (pending.exists() || Files.isSymbolicLink(pending.toPath())) {
            val staged = readPointerFile(root, pending)
                ?: throw SecurityException("Pending runtime pointer is invalid; refusing installation")
            if (!pending.renameTo(pointer)) throw IOException("Unable to recover pending runtime pointer")
            return staged
        }
        return null
    }

    private fun readPointerFile(root: File, pointer: File): ActiveInstalledRuntime? {
        if (!pointer.isFile || Files.isSymbolicLink(pointer.toPath())) return null
        return try {
            val json = JSONObject(pointer.readText(Charsets.UTF_8))
            if (json.optInt("schemaVersion", -1) != 1) return null
            val version = json.optLong("version", -1L)
            val name = json.optString("directoryName", "")
            val digest = json.optString("sha256", "")
            if (version <= 0L || name.isBlank() || File(name).name != name ||
                name.contains("..") || !digest.matches(Regex("^[0-9a-fA-F]{64}$")) ||
                name != "runtime-$version-${digest.lowercase().take(12)}"
            ) return null
            validateInstalledDirectory(root, name)
            ActiveInstalledRuntime(version, name, digest.lowercase())
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

    private fun writePointerTransactionally(root: File, active: ActiveInstalledRuntime) {
        val pointer = File(root, POINTER_NAME)
        val pending = File(root, PENDING_NAME)
        val backup = File(root, BACKUP_NAME)
        // Do not follow or silently overwrite redirected metadata paths, including
        // dangling symlinks for which File.exists() can return false.
        if (listOf(pointer, pending, backup).any { Files.isSymbolicLink(it.toPath()) }) {
            throw SecurityException("Runtime pointer metadata must not be symbolic links")
        }
        if (pending.exists() && !pending.delete()) throw IOException("Cannot clear stale pending pointer")
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("version", active.version)
            .put("directoryName", active.directoryName)
            .put("sha256", active.sha256)
        pending.writeText(json.toString(), Charsets.UTF_8)
        val check = JSONObject(pending.readText(Charsets.UTF_8))
        if (check.optLong("version", -1L) != active.version ||
            check.optString("directoryName", "") != active.directoryName ||
            check.optString("sha256", "") != active.sha256
        ) {
            pending.delete()
            throw IOException("Installed runtime pointer failed read-back verification")
        }
        if (listOf(pointer, pending, backup).any { Files.isSymbolicLink(it.toPath()) }) {
            pending.delete()
            throw SecurityException("Runtime pointer metadata changed to a symbolic link")
        }
        if (backup.exists() && !backup.delete()) {
            pending.delete()
            throw IOException("Cannot clear previous pointer backup")
        }
        if (pointer.exists() && !pointer.renameTo(backup)) {
            pending.delete()
            throw IOException("Cannot preserve previous installed runtime pointer")
        }
        if (!pending.renameTo(pointer)) {
            if (backup.exists()) backup.renameTo(pointer)
            pending.delete()
            throw IOException("Unable to publish active installed runtime pointer")
        }
    }
}
