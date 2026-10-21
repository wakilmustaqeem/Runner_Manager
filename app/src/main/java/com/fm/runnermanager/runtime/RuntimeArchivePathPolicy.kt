package com.fm.runnermanager.runtime

/**
 * Pure path policy for a future archive extractor. Call this for every archive entry
 * before creating any file. Symlink targets must also be checked relative to the
 * entry's parent directory; do not follow links while extracting.
 */
object RuntimeArchivePathPolicy {
    fun isSafeEntryPath(path: String): Boolean {
        if (path.isEmpty() || path.indexOf('\u0000') >= 0) return false
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false

        val segments = path.split("/")
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    /**
     * Checks a relative symlink target without following filesystem links.
     * [entryPath] is the archive path of the link itself.
     */
    fun isSafeSymlinkTarget(entryPath: String, target: String): Boolean {
        if (!isSafeEntryPath(entryPath)) return false
        if (target.isEmpty() || target.indexOf('\u0000') >= 0) return false
        if (target.startsWith("/") || target.startsWith("\\") || target.contains("\\")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(target)) return false

        val stack = entryPath.split("/").dropLast(1).toMutableList()
        for (segment in target.split("/")) {
            when (segment) {
                "", "." -> return false
                ".." -> {
                    if (stack.isEmpty()) return false
                    stack.removeAt(stack.lastIndex)
                }
                else -> stack += segment
            }
        }
        return stack.isNotEmpty()
    }
}
