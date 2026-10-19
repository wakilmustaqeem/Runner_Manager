/*
 * Bounded ZIP extraction into an empty private staging directory.
 *
 * This implementation does not install or activate a runtime. The caller must
 * supply a newly-created private staging directory and verify the extracted
 * runtime layout and signed manifest before any activation.
 */
package com.fm.runnermanager.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class RuntimeArchiveExtractor(
    private val maxEntries: Int = 50_000,
    private val maxEntryBytes: Long = 512L * 1024 * 1024,
    private val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024
) {
    sealed interface Result {
        data class Extracted(val filesWritten: Int, val bytesWritten: Long) : Result
        data class Blocked(val reason: String) : Result
    }

    fun extract(archive: File, stagingRoot: File): Result {
        if (!archive.isFile || Files.isSymbolicLink(archive.toPath())) {
            return Result.Blocked("archive is not a regular file")
        }
        if (!stagingRoot.isDirectory || Files.isSymbolicLink(stagingRoot.toPath())) {
            return Result.Blocked("staging root must be an existing real directory")
        }
        val root = try {
            stagingRoot.canonicalFile.toPath()
        } catch (_: IOException) {
            return Result.Blocked("unable to resolve staging root")
        } catch (_: SecurityException) {
            return Result.Blocked("filesystem access denied for staging root")
        }
        val initialEntries = stagingRoot.list()
        if (initialEntries == null || initialEntries.isNotEmpty()) {
            return Result.Blocked("staging root must be empty")
        }

        val policy = RuntimeArchiveExtractionPolicy(maxEntries, maxEntryBytes, maxTotalBytes)
        val created = mutableListOf<File>()
        var filesWritten = 0
        var bytesWritten = 0L

        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val kind = if (entry.isDirectory) {
                        RuntimeArchiveExtractionPolicy.EntryKind.DIRECTORY
                    } else {
                        RuntimeArchiveExtractionPolicy.EntryKind.FILE
                    }
                    val decision = policy.accept(entry.name, kind, entry.size)
                    if (decision !is RuntimeArchiveExtractionPolicy.Decision.Allow) {
                        val reason = (decision as RuntimeArchiveExtractionPolicy.Decision.Block).reason
                        throw ExtractionBlocked(reason)
                    }

                    val destination = File(stagingRoot, decision.normalizedPath)
                    val canonicalDestination = destination.canonicalFile.toPath()
                    if (canonicalDestination == root || !canonicalDestination.startsWith(root)) {
                        throw ExtractionBlocked("archive destination escapes staging root")
                    }
                    ensureNoSymlinkParents(root, canonicalDestination)

                    if (kind == RuntimeArchiveExtractionPolicy.EntryKind.DIRECTORY) {
                        createDirectoriesSafely(root, canonicalDestination, created)
                    } else {
                        val parent = canonicalDestination.parent
                        ?: throw ExtractionBlocked("archive file has no parent directory")
                    createDirectoriesSafely(root, parent, created)
                    if (Files.exists(canonicalDestination, LinkOption.NOFOLLOW_LINKS)) {
                        throw ExtractionBlocked("archive destination already exists")
                    }

                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(
                            canonicalDestination,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS
                        ).use { output ->
                            created.add(canonicalDestination.toFile())
                            val buffer = ByteArray(32 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                var count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) {
                                    val one = input.read()
                                    if (one < 0) break
                                    if (maxEntryBytes - entryBytes < 1L ||
                                        maxTotalBytes - bytesWritten < 1L
                                    ) {
                                        throw ExtractionBlocked("actual archive byte limit exceeded")
                                    }
                                    output.write(one)
                                    entryBytes += 1L
                                    bytesWritten += 1L
                                    continue
                                }
                                val chunk = count.toLong()
                                if (chunk > maxEntryBytes - entryBytes) {
                                    throw ExtractionBlocked("actual entry bytes exceed configured limit")
                                }
                                if (chunk > maxTotalBytes - bytesWritten) {
                                    throw ExtractionBlocked("actual archive bytes exceed configured total limit")
                                }
                                output.write(buffer, 0, count)
                                entryBytes += chunk
                                bytesWritten += chunk
                            }
                            if (entry.size >= 0L && entryBytes != entry.size) {
                                throw ExtractionBlocked("actual entry size differs from archive metadata")
                            }
                        }
                    }
                        filesWritten++
                    }
                }
            }
            return Result.Extracted(filesWritten, bytesWritten)
        } catch (e: ExtractionBlocked) {
            cleanupCreated(created)
            return Result.Blocked(e.message ?: "archive extraction blocked")
        } catch (e: IOException) {
            cleanupCreated(created)
            return Result.Blocked("archive extraction failed due to an I/O error")
        } catch (e: SecurityException) {
            cleanupCreated(created)
            return Result.Blocked("filesystem access denied during extraction")
        } catch (e: IllegalArgumentException) {
            cleanupCreated(created)
            return Result.Blocked("archive metadata is invalid")
        }
    }

    private fun ensureNoSymlinkParents(root: java.nio.file.Path, destination: java.nio.file.Path) {
        var current = destination
        while (current != root && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) {
                throw ExtractionBlocked("symbolic link found in staging path")
            }
            current = current.parent ?: break
        }
        if (current != root) throw ExtractionBlocked("destination is outside staging root")
    }

    private fun createDirectoriesSafely(
        root: java.nio.file.Path,
        directory: java.nio.file.Path,
        created: MutableList<File>
    ) {
        if (!directory.startsWith(root)) throw ExtractionBlocked("directory escapes staging root")
        val missing = mutableListOf<java.nio.file.Path>()
        var current: java.nio.file.Path? = directory
        while (current != null && current != root) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw ExtractionBlocked("path component is not a real directory")
                }
                break
            }
            missing.add(current)
            current = current.parent
        }
        if (current != root) throw ExtractionBlocked("directory is outside staging root")
        for (path in missing.asReversed()) {
            Files.createDirectory(path)
            created.add(path.toFile())
        }
    }

    private fun cleanupCreated(created: List<File>) {
        for (file in created.asReversed()) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: IOException) {
                // Preserve fail-closed result; caller must discard the staging directory.
            } catch (_: SecurityException) {
                // Preserve fail-closed result; caller must discard the staging directory.
            }
        }
    }

    private class ExtractionBlocked(message: String) : IOException(message)
}
