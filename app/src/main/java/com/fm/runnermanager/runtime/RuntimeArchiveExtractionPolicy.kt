/*
 * Bounded, fail-closed policy for entries in a future runtime archive extractor.
 *
 * This policy is not an extractor. Callers must enforce the returned decision
 * before writing bytes and must independently verify actual streamed byte
 * counts, staging-root containment, and filesystem object types.
 */
package com.fm.runnermanager.runtime

class RuntimeArchiveExtractionPolicy(
    private val maxEntries: Int = 50_000,
    private val maxEntryBytes: Long = 512L * 1024 * 1024,
    private val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024
) {
    enum class EntryKind { FILE, DIRECTORY, SYMBOLIC_LINK, HARD_LINK, SPECIAL }

    sealed interface Decision {
        data class Allow(val normalizedPath: String, val kind: EntryKind) : Decision
        data class Block(val reason: String) : Decision
    }

    private val acceptedPaths = mutableMapOf<String, EntryKind>()
    private var acceptedEntries = 0
    private var declaredTotalBytes = 0L

    @Synchronized
    fun accept(path: String, kind: EntryKind, declaredSize: Long): Decision {
        val validated = RuntimeArchivePathValidator.validate(path)
        if (validated !is RuntimeArchivePathValidator.Result.Safe) {
            return Decision.Block((validated as RuntimeArchivePathValidator.Result.Unsafe).reason)
        }
        if (kind == EntryKind.SYMBOLIC_LINK || kind == EntryKind.HARD_LINK || kind == EntryKind.SPECIAL) {
            return Decision.Block("links and special filesystem entries are not allowed")
        }
        if (declaredSize < 0L) return Decision.Block("negative declared entry size is invalid")
        if (kind == EntryKind.DIRECTORY && declaredSize != 0L) {
            return Decision.Block("directory entries must declare zero bytes")
        }
        if (kind == EntryKind.FILE && declaredSize > maxEntryBytes) {
            return Decision.Block("entry exceeds configured size limit")
        }
        if (acceptedEntries >= maxEntries) return Decision.Block("archive exceeds entry-count limit")

        val normalized = validated.relativePath
        if (acceptedPaths.containsKey(normalized)) {
            return Decision.Block("duplicate archive entry path")
        }
        // A previously declared file cannot be a parent directory of a later entry.
        var parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        while (parent.isNotEmpty()) {
            if (acceptedPaths[parent] == EntryKind.FILE) {
                return Decision.Block("archive path conflicts with an existing file")
            }
            parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
        }
        // A file cannot replace a directory that already has descendants.
        if (kind == EntryKind.FILE && acceptedPaths.keys.any { it.startsWith("$normalized/") }) {
            return Decision.Block("file entry conflicts with existing child entries")
        }
        if (kind == EntryKind.FILE && declaredSize > maxTotalBytes - declaredTotalBytes) {
            return Decision.Block("archive exceeds configured total uncompressed size limit")
        }

        acceptedPaths[normalized] = kind
        acceptedEntries += 1
        if (kind == EntryKind.FILE) declaredTotalBytes += declaredSize
        return Decision.Allow(normalized, kind)
    }
}
