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
        val digest = artifact.sha256.lowercase()
        if (!digest.matches(Regex("^[0-9a-f]{64}$"))) {
            throw SecurityException("Invalid staged runtime digest")
        }
        val directoryName = "runtime-${artifact.version}-${digest.take(12)}"
        if (File(directoryName).name != directoryName || directoryName.contains("..")) {
            throw SecurityException("Invalid installed runtime directory name")
        }

        val installedDir = File(root, directoryName)
        if (!installedDir.exists()) {
            RuntimeArchiveInstaller.install(artifact, installedDir, extractor)
        }
        validateInstalledDirectory(root, directoryName)
        val active = ActiveInstalledRuntime(artifact.version, directoryName, digest)
        writePointerTransactionally(root, active)
        return active
    }

    fun readActive(appPrivateRoot: File): ActiveInstalledRuntime? {
        if (!appPrivateRoot.isDirectory || Files.isSymbolicLink(appPrivateRoot.toPath())) return null
        val root = runCatching { appPrivateRoot.canonicalFile }.getOrNull() ?: return null
        val pointer = File(root, POINTER_NAME)
        if (!pointer.isFile || Files.isSymbolicLink(pointer.toPath())) return null
        return try {
            val json = JSONObject(pointer.readText(Charsets.UTF_8))
            if (json.optInt("schemaVersion", -1) != 1) return null
            val version = json.optLong("version", -1L)
            val name = json.optString("directoryName", "")
            val digest = json.optString("sha256", "")
            if (version <= 0L || name.isBlank() || File(name).name != name ||
                name.contains("..") || !name.startsWith("runtime-$version-") ||
                !digest.matches(Regex("^[0-9a-fA-F]{64}$"))
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
