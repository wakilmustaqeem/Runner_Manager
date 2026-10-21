package com.fm.runnermanager.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Fail-closed extraction sink for a future audited archive parser.
 *
 * Only regular files and directories are accepted. Symbolic links, hard links,
 * devices, FIFOs, and other special entries are rejected deliberately because
 * accepting links before a complete link-safe extractor exists can escape the
 * rootfs. This sink does not parse tar/zstd itself and must only be fed entries
 * by a parser that reports the true entry type and size.
 */
object SafeRuntimeExtractor {
    enum class EntryType { DIRECTORY, REGULAR_FILE, SYMBOLIC_LINK, HARD_LINK, SPECIAL }

    data class Entry(
        val path: String,
        val type: EntryType,
        val declaredBytes: Long = 0L,
        val openContent: (() -> InputStream)? = null,
    )

    data class Limits(
        val maxEntries: Int = 100_000,
        val maxSingleFileBytes: Long = 512L * 1024L * 1024L,
        val maxTotalBytes: Long = 2L * 1024L * 1024L * 1024L,
    )

    data class Result(val entriesWritten: Int, val bytesWritten: Long)

    fun extract(
        destination: File,
        entries: Sequence<Entry>,
        limits: Limits = Limits(),
    ): Result {
        require(limits.maxEntries > 0 && limits.maxSingleFileBytes > 0L && limits.maxTotalBytes > 0L) {
            "Extraction limits must be positive"
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Unable to create extraction directory")
        }
        if (!destination.isDirectory) throw IOException("Extraction destination is not a directory")
        val root = destination.canonicalFile
        var entryCount = 0
        var totalBytes = 0L

        for (entry in entries) {
            entryCount++
            if (entryCount > limits.maxEntries) throw SecurityException("Archive contains too many entries")
            val relative = validateRelativePath(entry.path)
            val target = File(root, relative).canonicalFile
            val rootPrefix = root.path + File.separator
            if (target.path != root.path && !target.path.startsWith(rootPrefix)) {
                throw SecurityException("Archive entry escapes extraction root")
            }
            ensureNoSymlinkParents(root, target)

            when (entry.type) {
                EntryType.SYMBOLIC_LINK, EntryType.HARD_LINK, EntryType.SPECIAL ->
                    throw SecurityException("Runtime archive contains unsupported link or special entry")
                EntryType.DIRECTORY -> {
                    if (entry.declaredBytes != 0L || entry.openContent != null) {
                        throw SecurityException("Directory entry has unexpected content")
                    }
                    if (!target.exists() && !target.mkdirs()) throw IOException("Unable to create archive directory")
                    if (!target.isDirectory || Files.isSymbolicLink(target.toPath())) {
                        throw SecurityException("Directory entry conflicts with an existing non-directory")
                    }
                }
                EntryType.REGULAR_FILE -> {
                    if (entry.declaredBytes < 0L || entry.declaredBytes > limits.maxSingleFileBytes) {
                        throw SecurityException("Archive file exceeds per-file limit")
                    }
                    if (totalBytes > limits.maxTotalBytes - entry.declaredBytes) {
                        throw SecurityException("Archive exceeds total extracted-size limit")
                    }
                    val contentFactory = entry.openContent
                        ?: throw SecurityException("Regular file entry has no content stream")
                    val parent = target.parentFile ?: throw SecurityException("Archive file has no parent")
                    if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create file parent")
                    ensureNoSymlinkParents(root, target)
                    if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                        throw SecurityException("Archive entry would overwrite an existing path")
                    }
                    val temp = File.createTempFile("fm-extract-", ".part", parent)
                    try {
                        contentFactory().use { input ->
                            temp.outputStream().buffered().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var fileBytes = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    fileBytes += count
                                    if (fileBytes > entry.declaredBytes ||
                                        fileBytes > limits.maxSingleFileBytes ||
                                        totalBytes > limits.maxTotalBytes - fileBytes
                                    ) {
                                        throw SecurityException("Archive content exceeded declared extraction limits")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                if (fileBytes != entry.declaredBytes) {
                                    throw IOException("Archive entry size differs from declared size")
                                }
                                totalBytes += fileBytes
                            }
                        }
                        ensureNoSymlinkParents(root, target)
                        if (target.exists()) throw SecurityException("Archive destination appeared during extraction")
                        try {
                            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), target.toPath())
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
        }
        return Result(entryCount, totalBytes)
    }

    private fun validateRelativePath(path: String): String {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") ||
            Regex("^[A-Za-z]:").containsMatchIn(path) || path.contains('\\u0000')
        ) throw SecurityException("Absolute or invalid archive path")
        val normalized = path.replace('\\', '/')
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw SecurityException("Archive path contains unsafe segment")
        }
        return parts.joinToString(File.separator)
    }

    private fun ensureNoSymlinkParents(root: File, target: File) {
        val rootPath = root.toPath()
        val targetPath = target.toPath()
        if (!targetPath.normalize().startsWith(rootPath)) {
            throw SecurityException("Archive path escapes extraction root")
        }
        var current = targetPath.parent
        while (current != null && current.startsWith(rootPath)) {
            if (Files.isSymbolicLink(current)) throw SecurityException("Symbolic-link parent is forbidden")
            if (current == rootPath) break
            current = current.parent
        }
        if (Files.isSymbolicLink(targetPath)) throw SecurityException("Symbolic-link destination is forbidden")
    }
}
