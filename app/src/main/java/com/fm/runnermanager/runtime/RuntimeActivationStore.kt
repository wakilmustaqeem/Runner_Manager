package com.fm.runnermanager.runtime

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Transactional metadata switch for a verified staged runtime.
 *
 * This does NOT extract or execute a runtime. The active pointer is only a
 * durable record for a future installer/launcher, and must never be treated
 * as proof that a runner process is online.
 */
object RuntimeActivationStore {
    data class ActiveRuntime(val version: Long, val archiveName: String, val sha256: String)

    private const val POINTER_NAME = "active-runtime.json"
    private const val TEMP_NAME = "active-runtime.json.pending"
    private const val BACKUP_NAME = "active-runtime.json.previous"

    @Synchronized
    fun activateVerifiedArchive(
        appPrivateRoot: File,
        stagedArchive: File,
        version: Long,
        expectedSha256: String,
    ): ActiveRuntime {
        require(version > 0L) { "Runtime version must be positive" }
        require(expectedSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "Invalid SHA-256"
        }
        if (!appPrivateRoot.exists() && !appPrivateRoot.mkdirs()) {
            throw IOException("Unable to create app-private runtime directory")
        }
        if (!appPrivateRoot.isDirectory) throw IOException("Runtime root is not a directory")

        val root = appPrivateRoot.canonicalFile
        val archive = stagedArchive.canonicalFile
        val prefix = root.path + File.separator
        if (!archive.path.startsWith(prefix) || !archive.isFile) {
            throw SecurityException("Staged archive must be a regular file inside app-private storage")
        }
        if (!RuntimePreflight.matchesSha256(archive, expectedSha256)) {
            throw SecurityException("Staged archive integrity verification failed")
        }

        val active = ActiveRuntime(version, archive.name, expectedSha256.lowercase())
        val pointer = File(root, POINTER_NAME)
        val pending = File(root, TEMP_NAME)
        val backup = File(root, BACKUP_NAME)
        if (pending.exists() && !pending.delete()) throw IOException("Cannot clear stale pending pointer")

        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("version", active.version)
            .put("archiveName", active.archiveName)
            .put("sha256", active.sha256)
        pending.writeText(json.toString(), Charsets.UTF_8)
        // Read-back prevents switching to a truncated/corrupt metadata record.
        val check = JSONObject(pending.readText(Charsets.UTF_8))
        if (check.optLong("version", -1L) != active.version ||
            check.optString("archiveName", "") != active.archiveName ||
            check.optString("sha256", "") != active.sha256
        ) {
            pending.delete()
            throw IOException("Pending runtime pointer failed read-back verification")
        }

        if (backup.exists() && !backup.delete()) {
            pending.delete()
            throw IOException("Cannot clear old runtime pointer backup")
        }
        if (pointer.exists() && !pointer.renameTo(backup)) {
            pending.delete()
            throw IOException("Cannot preserve previous runtime pointer")
        }
        if (!pending.renameTo(pointer)) {
            // Best-effort rollback: restore the previous pointer if activation failed.
            if (backup.exists()) backup.renameTo(pointer)
            pending.delete()
            throw IOException("Unable to commit active runtime pointer")
        }
        return active
    }

    fun readActive(appPrivateRoot: File): ActiveRuntime? {
        val pointer = File(appPrivateRoot, POINTER_NAME)
        if (!pointer.isFile) return null
        return try {
            val json = JSONObject(pointer.readText(Charsets.UTF_8))
            if (json.optInt("schemaVersion", -1) != 1) return null
            val version = json.optLong("version", -1L)
            val name = json.optString("archiveName", "")
            val digest = json.optString("sha256", "")
            if (version <= 0L || name.isBlank() || File(name).name != name ||
                !digest.matches(Regex("^[0-9a-fA-F]{64}$"))
            ) return null
            ActiveRuntime(version, name, digest.lowercase())
        } catch (_: Exception) {
            null
        }
    }
}
