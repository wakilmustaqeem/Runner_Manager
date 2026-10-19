/*
 * Independent bounded TAR.GZ extractor for the FM runtime installer.
 *
 * DroidRunner's public runtime bundle is tar.gz, not ZIP. This implementation
 * uses Apache Commons Compress for archive decoding but owns the path, link,
 * entry-count, byte-count, and cleanup policy here.
 *
 * Modified/adapted for FM Runner Manager on 2026-10-19. Do not treat extraction
 * as provenance verification: signature, manifest fields, archive digest and
 * final runtime layout must pass independently before activation.
 */
package com.fm.runnermanager.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class RuntimeTarGzExtractor(
    private val maxEntries: Int = 50_000,
    private val maxEntryBytes: Long = 512L * 1024 * 1024,
    private val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024
) {
    data class Result(val filesWritten: Int, val bytesWritten: Long, val entriesSeen: Int)

    fun extract(archive: File, stagingDirectory: File): Result {
        require(maxEntries > 0 && maxEntryBytes >= 0 && maxTotalBytes >= 0) {
            "Invalid extraction limits"
        }
        require(archive.isFile) { "Runtime archive is missing" }
        val rootFile = stagingDirectory.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(rootFile)) { "Staging root must not be a symbolic link" }
        Files.createDirectories(rootFile)
        require(Files.isDirectory(rootFile, LinkOption.NOFOLLOW_LINKS)) {
            "Staging root is not a directory"
        }
        Files.newDirectoryStream(rootFile).use { stream ->
            require(!stream.iterator().hasNext()) { "Staging directory must be empty" }
        }

        val seen = linkedMapOf<String, Kind>()
        val created = mutableListOf<Path>()
        var entriesSeen = 0
        var filesWritten = 0
        var totalBytes = 0L

        try {
            BufferedInputStream(archive.inputStream()).use { buffered ->
                GzipCompressorInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            entriesSeen++
                            if (entriesSeen > maxEntries) throw IOException("Archive exceeds entry-count limit")
                            val normalized = validatePath(entry.name)
                            val kind = classify(entry)
                            val previous = seen[normalized]
                            if (previous != null) throw IOException("Duplicate archive entry path")
                            var parentName = normalized.substringBeforeLast('/', "")
                            while (parentName.isNotEmpty()) {
                                if (seen[parentName] == Kind.FILE || seen[parentName] == Kind.SYMLINK) {
                                    throw IOException("Archive entry traverses a non-directory entry")
                                }
                                parentName = parentName.substringBeforeLast('/', "")
                            }
                            if (kind == Kind.FILE && seen.keys.any { it.startsWith("$normalized/") }) {
                                throw IOException("File entry conflicts with existing children")
                            }
                            val declaredSize = entry.size
                            if (declaredSize < 0L) throw IOException("Negative TAR entry size")
                            if (kind != Kind.FILE && declaredSize != 0L) {
                                throw IOException("Non-file TAR entry declares a payload")
                            }
                            if (kind == Kind.FILE && declaredSize > maxEntryBytes) {
                                throw IOException("Entry exceeds configured size limit")
                            }
                            if (kind == Kind.FILE && declaredSize > maxTotalBytes - totalBytes) {
                                throw IOException("Archive exceeds total uncompressed size limit")
                            }

                            val destination = rootFile.resolve(normalized).normalize()
                            if (!destination.startsWith(rootFile) || destination == rootFile) {
                                throw IOException("Archive path escapes staging root")
                            }
                            ensureSafeParents(rootFile, destination.parent, created)

                            when (kind) {
                                Kind.DIRECTORY -> {
                                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                                        if (Files.isSymbolicLink(destination) ||
                                            !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                                            throw IOException("Directory entry conflicts with existing path")
                                        }
                                    } else {
                                        Files.createDirectory(destination)
                                        created += destination
                                    }
                                }
                                Kind.FILE -> {
                                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                                        throw IOException("File entry conflicts with existing path")
                                    }
                                    Files.newOutputStream(
                                        destination,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS
                                    ).use { output ->
                                        created += destination
                                        val buffer = ByteArray(32 * 1024)
                                        var entryBytes = 0L
                                        while (entryBytes < declaredSize) {
                                            val requested = minOf(buffer.size.toLong(), declaredSize - entryBytes).toInt()
                                            val count = tar.read(buffer, 0, requested)
                                            if (count < 0) throw IOException("Truncated TAR file entry")
                                            if (count == 0) {
                                                val one = tar.read()
                                                if (one < 0) throw IOException("Truncated TAR file entry")
                                                output.write(one)
                                                entryBytes++
                                                totalBytes++
                                            } else {
                                                output.write(buffer, 0, count)
                                                entryBytes += count
                                                totalBytes += count
                                            }
                                            if (entryBytes > maxEntryBytes || totalBytes > maxTotalBytes) {
                                                throw IOException("Actual extracted bytes exceed configured limits")
                                            }
                                        }
                                        if (entryBytes != declaredSize) throw IOException("TAR entry size mismatch")
                                    }
                                    if (entry.mode and 0b001001001 != 0) {
                                        destination.toFile().setExecutable(true, false)
                                    }
                                    filesWritten++
                                }
                                Kind.SYMLINK -> {
                                    val target = entry.linkName
                                    val safeTarget = resolveSymlinkTarget(normalized, target)
                                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                                        throw IOException("Symbolic link conflicts with existing path")
                                    }
                                    Files.createSymbolicLink(destination, safeTarget)
                                    created += destination
                                }
                            }
                            seen[normalized] = kind
                        }
                    }
                }
            }
            return Result(filesWritten, totalBytes, entriesSeen)
        } catch (failure: Exception) {
            cleanupCreated(created)
            throw failure
        }
    }

    private enum class Kind { FILE, DIRECTORY, SYMLINK }

    private fun classify(entry: TarArchiveEntry): Kind = when {
        entry.isDirectory -> Kind.DIRECTORY
        entry.isSymbolicLink -> Kind.SYMLINK
        entry.isFile -> Kind.FILE
        else -> throw IOException("Hard links and special TAR entries are not allowed")
    }

    private fun validatePath(raw: String): String {
        return when (val result = RuntimeArchivePathValidator.validate(raw)) {
            is RuntimeArchivePathValidator.Result.Safe -> result.relativePath
            is RuntimeArchivePathValidator.Result.Unsafe -> throw IOException(result.reason)
        }
    }

    /**
     * Map Linux absolute symlinks inside the runtime rootfs to relative links
     * that remain inside the staged rootfs. Creating a host-absolute symlink
     * such as /usr/bin/env would point outside the staged runtime on Android.
     */
    private fun resolveSymlinkTarget(entryPath: String, rawTarget: String): Path {
        if (rawTarget.isEmpty() || rawTarget.any { it == '\\u0000' || it.code < 0x20 } ||
            '\\\\' in rawTarget
        ) {
            throw IOException("Invalid symbolic-link target")
        }
        val rootfs = Paths.get("rootfs")
        val entry = Paths.get(entryPath)
        if (!entry.startsWith(rootfs) || entry.nameCount < 2) {
            throw IOException("Symbolic links are only allowed inside rootfs")
        }
        val targetInsideRoot = if (rawTarget.startsWith('/')) {
            val absoluteGuestTarget = rawTarget.removePrefix("/")
            if (absoluteGuestTarget.split('/').any { it == ".." || it == "." }) {
                throw IOException("Unsafe absolute guest symlink target")
            }
            rootfs.resolve(absoluteGuestTarget).normalize()
        } else {
            entry.parent.resolve(rawTarget).normalize()
        }
        if (!targetInsideRoot.startsWith(rootfs)) {
            throw IOException("Symbolic link escapes staged rootfs")
        }
        return entry.parent.relativize(targetInsideRoot)
    }

    private fun ensureSafeParents(root: Path, parent: Path?, created: MutableList<Path>) {
        if (parent == null || !parent.startsWith(root)) throw IOException("Destination parent escapes staging root")
        var current = root
        for (part in root.relativize(parent)) {
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) ||
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Destination parent is not a real directory")
                }
            } else {
                Files.createDirectory(current)
                created += current
            }
        }
    }

    private fun cleanupCreated(created: List<Path>) {
        for (path in created.asReversed()) {
            runCatching { Files.deleteIfExists(path) }
        }
    }
}
