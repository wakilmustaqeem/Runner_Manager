/*
 * Resolves validated archive entry names beneath an extraction staging root.
 *
 * This is a lexical containment check only. The extractor must create and
 * validate a private, newly-created staging directory and must reject symlink
 * entries/parents; this helper does not defend against filesystem races.
 */
package com.fm.runnermanager.runtime

import java.io.File

object RuntimeArchiveDestinationResolver {
    sealed interface Result {
        data class Resolved(val destination: File) : Result
        data class Blocked(val reason: String) : Result
    }

    fun resolve(stagingRoot: File, archiveEntryName: String): Result {
        val validated = RuntimeArchivePathValidator.validate(archiveEntryName)
        if (validated !is RuntimeArchivePathValidator.Result.Safe) {
            val reason = (validated as RuntimeArchivePathValidator.Result.Unsafe).reason
            return Result.Blocked(reason)
        }

        return try {
            val root = stagingRoot.canonicalFile.toPath()
            val destination = File(stagingRoot, validated.relativePath).canonicalFile.toPath()
            if (destination == root || !destination.startsWith(root)) {
                Result.Blocked("archive destination escapes staging root")
            } else {
                Result.Resolved(destination.toFile())
            }
        } catch (_: java.io.IOException) {
            Result.Blocked("unable to resolve archive destination")
        } catch (_: SecurityException) {
            Result.Blocked("filesystem access denied while resolving destination")
        }
    }
}
