package com.fm.runnermanager.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

class RuntimePointerTransaction(
    private val root: File,
    private val minimumAllowedVersion: Long,
    private val verifyDirectory: (RuntimePointer) -> Boolean = { true },
    private val nowEpochSeconds: () -> Long = {
        System.currentTimeMillis() / 1000L
    },
) {
    companion object {
        const val POINTER_NAME = "active-installed-runtime.json"
        const val PENDING_NAME = "active-installed-runtime.json.pending"
        const val PREVIOUS_NAME = "active-installed-runtime.json.previous"
        const val COMMIT_NAME = "active-installed-runtime.json.commit"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }

    sealed class RecoveryResult {
        data class Active(val pointer: RuntimePointer) : RecoveryResult()
        data object SafeFailure : RecoveryResult()
    }

    private val pointerFile = File(root, POINTER_NAME)
    private val pendingFile = File(root, PENDING_NAME)
    private val previousFile = File(root, PREVIOUS_NAME)
    private val commitFile = File(root, COMMIT_NAME)

    init {
        require(root.isDirectory || root.mkdirs()) {
            "Cannot create runtime root: $root"
        }
        require(!Files.isSymbolicLink(root.toPath())) {
            "Runtime root must not be a symlink"
        }
    }

    @Synchronized
    fun writeActive(
        version: Long,
        directoryName: String,
        sha256: String,
    ): RuntimePointer {
        require(version >= minimumAllowedVersion) {
            "version $version below floor $minimumAllowedVersion"
        }
        val normalizedSha = sha256.lowercase()
        require(SHA256_HEX.matches(normalizedSha)) { "Invalid sha256" }
        val expectedName = "runtime-$version-${normalizedSha.take(12)}"
        require(directoryName == expectedName) {
            "directoryName must be $expectedName"
        }

        if (commitFile.exists()) {
            throw IllegalStateException(
                "Stale commit marker present; call recover() first",
            )
        }
        if (pendingFile.exists()) {
            throw IllegalStateException(
                "Stale pending pointer present; call recover() first",
            )
        }

        requireNoSymlink(pointerFile, pendingFile, previousFile, commitFile)

        val installedDir = File(root, directoryName)
        if (!installedDir.exists() || !installedDir.isDirectory) {
            throw IOException("Installed runtime directory not found")
        }
        if (Files.isSymbolicLink(installedDir.toPath())) {
            throw SecurityException("Installed runtime directory is a symlink")
        }
        val canonicalRoot = root.canonicalFile
        val canonicalDir = installedDir.canonicalFile
        if (canonicalDir.parentFile != canonicalRoot) {
            throw SecurityException(
                "Installed runtime directory escapes runtime root",
            )
        }

        val txnId = RuntimePointer.newTransactionId()
        val pointer = RuntimePointer(
            schemaVersion = RuntimePointer.SCHEMA_VERSION,
            transactionId = txnId,
            version = version,
            directoryName = directoryName,
            sha256 = normalizedSha,
            activatedAtEpochSeconds = nowEpochSeconds(),
        )
        RuntimePointer.validate(pointer)

        val marker = CommitMarker(
            schemaVersion = CommitMarker.SCHEMA_VERSION,
            transactionId = txnId,
            pendingVersion = version,
            pendingSha256 = normalizedSha,
        )
        CommitMarker.validate(marker)

        writeAndSync(pendingFile, RuntimePointer.serialize(pointer))
        val reread = RuntimePointer.parse(pendingFile.readBytes())
        check(reread == pointer) { "pending round-trip mismatch" }

        if (pointerFile.exists() && !Files.isSymbolicLink(pointerFile.toPath())) {
            writeAndSync(previousFile, pointerFile.readBytes())
        }

        writeAndSync(commitFile, CommitMarker.serialize(marker))

        try {
            Files.move(
                pendingFile.toPath(),
                pointerFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "ATOMIC_MOVE not supported; transaction left for recover()",
                e,
            )
        }

        // Step 8: best-effort cleanup
        commitFile.delete()
        previousFile.delete()
        return pointer
    }

    // -----------------------------------------------------------------
    // Recovery (§13 matrix, commit binds via txnId + version + sha256)
    // -----------------------------------------------------------------

    @Synchronized
    fun recover(): RecoveryResult {
        // Symlink guard on all transaction files.
        for (f in listOf(pointerFile, pendingFile, previousFile, commitFile)) {
            if (f.exists() && Files.isSymbolicLink(f.toPath())) {
                return RecoveryResult.SafeFailure
            }
        }

        val commitExists = commitFile.exists()
        val commit = if (commitExists) tryReadCommit(commitFile) else null
        val pending = tryReadPointer(pendingFile)
        val active = tryReadPointer(pointerFile)
        val previous = tryReadPointer(previousFile)

        // Corrupt artefact cleanup: presence + parse failure = delete.
        if (commitExists && commit == null) commitFile.delete()
        if (pendingFile.exists() && pending == null) pendingFile.delete()
        if (previousFile.exists() && previous == null) previousFile.delete()

        val activeValid = active != null && runtimeStillValid(active)
        val pendingValid = pending != null &&
            pending.version >= minimumAllowedVersion &&
            runtimeStillValid(pending)
        val previousValid = previous != null &&
            previous.version >= minimumAllowedVersion &&
            runtimeStillValid(previous)

        // Rows 1–3 / 9–11: no valid commit marker.
        if (commit == null) {
            if (pending != null) pendingFile.delete()
            if (activeValid) return RecoveryResult.Active(active!!)
            if (previousValid) return rollbackTo(previous!!)
            return RecoveryResult.SafeFailure
        }

        val pendingBinds = pending != null && bindsTo(commit, pending)
        val activeBinds = active != null && bindsTo(commit, active)

        // Row 8: orphan commit.
        if (!pendingBinds && !activeBinds) {
            commitFile.delete()
            if (pending != null) pendingFile.delete()
            if (activeValid) return RecoveryResult.Active(active!!)
            if (previousValid) return rollbackTo(previous!!)
            return RecoveryResult.SafeFailure
        }

        // Rows 4–5: commit binds to pending.
        if (pendingBinds) {
            val versionFloor = maxOf(
                if (activeValid) active!!.version else 0L,
                if (previousValid) previous!!.version else 0L,
            )
            if (pendingValid && pending!!.version > versionFloor) {
                return rollForwardPending(pending)
            }
            // Row 5: no roll forward.
            pendingFile.delete()
            commitFile.delete()
            if (activeValid) return RecoveryResult.Active(active!!)
            if (previousValid) return rollbackTo(previous!!)
            return RecoveryResult.SafeFailure
        }

        // Rows 6–7: commit binds to active (committed → never re-activate).
        pendingFile.delete()
        commitFile.delete()
        if (activeValid) {
            previousFile.delete()
            return RecoveryResult.Active(active!!)
        }
        if (previousValid) return rollbackTo(previous!!)
        previousFile.delete()
        return RecoveryResult.SafeFailure
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun bindsTo(commit: CommitMarker, p: RuntimePointer): Boolean =
        commit.transactionId == p.transactionId &&
            commit.pendingVersion == p.version &&
            commit.pendingSha256 == p.sha256

    private fun rollForwardPending(pending: RuntimePointer): RecoveryResult =
        try {
            Files.move(
                pendingFile.toPath(),
                pointerFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            commitFile.delete()
            previousFile.delete()
            RecoveryResult.Active(pending)
        } catch (_: AtomicMoveNotSupportedException) {
            RecoveryResult.SafeFailure
        } catch (_: IOException) {
            RecoveryResult.SafeFailure
        }

    private fun rollbackTo(previous: RuntimePointer): RecoveryResult =
        try {
            Files.move(
                previousFile.toPath(),
                pointerFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            RecoveryResult.Active(previous)
        } catch (_: AtomicMoveNotSupportedException) {
            RecoveryResult.SafeFailure
        } catch (_: IOException) {
            RecoveryResult.SafeFailure
        }

    private fun runtimeStillValid(p: RuntimePointer): Boolean {
        val dir = File(root, p.directoryName)
        if (!dir.exists()) return false
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(dir.toPath())) return false
        return try {
            val canonicalRoot = root.canonicalFile
            val canonicalDir = dir.canonicalFile
            if (canonicalDir.parentFile != canonicalRoot) return false
            verifyDirectory(p)
        } catch (_: Exception) {
            false
        }
    }

    private fun tryReadPointer(file: File): RuntimePointer? {
        if (!file.exists() || !file.isFile) return null
        if (Files.isSymbolicLink(file.toPath())) return null
        return try {
            RuntimePointer.parse(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    private fun tryReadCommit(file: File): CommitMarker? {
        if (!file.exists() || !file.isFile) return null
        if (Files.isSymbolicLink(file.toPath())) return null
        return try {
            CommitMarker.parse(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun requireNoSymlink(vararg files: File) {
        for (f in files) {
            if (f.exists() && Files.isSymbolicLink(f.toPath())) {
                throw SecurityException(
                    "Transaction file must not be a symlink: ${f.name}",
                )
            }
        }
    }
}
