package com.fm.runnermanager.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Verifies a previously signed/digested staged artifact, extracts it into a new
 * private sibling directory, validates the rootfs boundary, then publishes the
 * complete directory. This does not activate or execute the runtime.
 */
object RuntimeArchiveInstaller {
    data class InstalledRuntime(
        val version: Long,
        val directory: File,
        val rootfsDirectory: File,
        val filesWritten: Int,
        val bytesWritten: Long,
        val sha256: String,
    )

    fun install(
        artifact: RuntimeArtifactStager.StagedArtifact,
        destination: File,
        extractor: RuntimeTarGzExtractor = RuntimeTarGzExtractor(),
    ): InstalledRuntime {
        require(artifact.version > 0L) { "Invalid staged runtime version" }
        require(artifact.archiveBytes > 0L) { "Invalid staged archive size" }
        if (!artifact.file.isFile || artifact.file.length() != artifact.archiveBytes) {
            throw SecurityException("Staged runtime archive is missing or has an unexpected size")
        }
        if (!RuntimePreflight.matchesSha256(artifact.file, artifact.sha256)) {
            throw SecurityException("Staged runtime archive failed SHA-256 verification")
        }

        val requested = destination.absoluteFile
        val parent = requested.parentFile ?: throw IOException("Runtime destination has no parent")
        if (!parent.isDirectory) throw IOException("Runtime destination parent must already exist")
        val canonicalParent = parent.canonicalFile
        val finalDestination = File(canonicalParent, requested.name)
        if (requested.canonicalFile.parentFile != canonicalParent) {
            throw SecurityException("Runtime destination must be a direct child of its parent")
        }
        if (finalDestination.exists() || Files.isSymbolicLink(finalDestination.toPath())) {
            throw IOException("Runtime destination already exists; refusing to overwrite")
        }

        val staging = Files.createTempDirectory(canonicalParent.toPath(), "fm-runtime-install-").toFile()
        try {
            val extraction = extractor.extract(artifact.file, staging)
            val rootfs = File(staging, "rootfs")
            if (Files.isSymbolicLink(rootfs.toPath()) ||
                !Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                throw SecurityException("Runtime archive is missing a real rootfs directory")
            }
            if (finalDestination.exists() || Files.isSymbolicLink(finalDestination.toPath())) {
                throw SecurityException("Runtime destination appeared before publish")
            }
            try {
                Files.move(staging.toPath(), finalDestination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                throw IOException(
                    "ATOMIC_MOVE unsupported; refusing non-atomic publish",
                    e,
                )
            }
            return InstalledRuntime(
                version = artifact.version,
                directory = finalDestination,
                rootfsDirectory = File(finalDestination, "rootfs"),
                filesWritten = extraction.filesWritten,
                bytesWritten = extraction.bytesWritten,
                sha256 = artifact.sha256.lowercase(),
            )
        } catch (failure: Exception) {
            staging.deleteRecursively()
            throw failure
        }
    }
}
