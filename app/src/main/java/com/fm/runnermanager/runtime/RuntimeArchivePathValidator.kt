/*
 * Strict archive-entry path gate shared by runtime archive extraction.
 *
 * This helper validates names only. The extractor must also resolve each
 * destination under a private staging root and defend against link traversal.
 */
package com.fm.runnermanager.runtime

object RuntimeArchivePathValidator {
    sealed interface Result {
        data class Safe(val relativePath: String) : Result
        data class Unsafe(val reason: String) : Result
    }

    fun validate(entryName: String): Result {
        if (entryName.isEmpty()) return Result.Unsafe("empty archive entry name")
        if ('\u0000' in entryName) return Result.Unsafe("NUL byte in archive entry name")
        if ('\\' in entryName) return Result.Unsafe("backslash path separator is not allowed")
        if (entryName.startsWith('/')) return Result.Unsafe("absolute archive path is not allowed")
        if (entryName.startsWith("//")) return Result.Unsafe("network-style absolute path is not allowed")
        if (Regex("^[A-Za-z]:").containsMatchIn(entryName)) {
            return Result.Unsafe("drive-qualified archive path is not allowed")
        }
        if (entryName.any { it.code < 0x20 }) {
            return Result.Unsafe("control character in archive entry name")
        }

        // TAR/ZIP directory entries conventionally end in one slash.
        val relative = if (entryName.endsWith('/')) entryName.dropLast(1) else entryName
        if (relative.isEmpty()) return Result.Unsafe("root directory entry is not allowed")
        if (relative.startsWith('/')) return Result.Unsafe("absolute archive path is not allowed")

        val segments = relative.split('/')
        if (segments.any { it.isEmpty() }) {
            return Result.Unsafe("empty path segment is not allowed")
        }
        if (segments.any { it == "." || it == ".." }) {
            return Result.Unsafe("dot path segments are not allowed")
        }
        if (segments.any { ':' in it }) {
            return Result.Unsafe("colon in path segment is not allowed")
        }

        return Result.Safe(relative)
    }
}
