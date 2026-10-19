package com.fm.runnermanager.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArchiveExtractionPolicyTest {
    @Test
    fun acceptsSafeFileAndDirectoryEntries() {
        val policy = RuntimeArchiveExtractionPolicy()
        assertTrue(policy.accept("rootfs/", RuntimeArchiveExtractionPolicy.EntryKind.DIRECTORY, 0) is RuntimeArchiveExtractionPolicy.Decision.Allow)
        assertTrue(policy.accept("rootfs/usr/bin/env", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 100) is RuntimeArchiveExtractionPolicy.Decision.Allow)
    }

    @Test
    fun rejectsLinksAndSpecialEntries() {
        for (kind in listOf(
            RuntimeArchiveExtractionPolicy.EntryKind.SYMBOLIC_LINK,
            RuntimeArchiveExtractionPolicy.EntryKind.HARD_LINK,
            RuntimeArchiveExtractionPolicy.EntryKind.SPECIAL
        )) {
            assertTrue(RuntimeArchiveExtractionPolicy().accept("rootfs/link", kind, 0) is RuntimeArchiveExtractionPolicy.Decision.Block)
        }
    }

    @Test
    fun rejectsDuplicatePaths() {
        val policy = RuntimeArchiveExtractionPolicy()
        policy.accept("rootfs/file", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1)
        assertTrue(policy.accept("rootfs/file", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }

    @Test
    fun rejectsParentTraversal() {
        assertTrue(RuntimeArchiveExtractionPolicy().accept("rootfs/../outside", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }

    @Test
    fun rejectsOversizedEntryAndTotalArchive() {
        assertTrue(
            RuntimeArchiveExtractionPolicy(maxEntryBytes = 4, maxTotalBytes = 8)
                .accept("one", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 5)
                is RuntimeArchiveExtractionPolicy.Decision.Block
        )
        val policy = RuntimeArchiveExtractionPolicy(maxEntryBytes = 8, maxTotalBytes = 5)
        assertTrue(policy.accept("one", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 4) is RuntimeArchiveExtractionPolicy.Decision.Allow)
        assertTrue(policy.accept("two", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 2) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }

    @Test
    fun rejectsEntryCountOverflow() {
        val policy = RuntimeArchiveExtractionPolicy(maxEntries = 1)
        assertTrue(policy.accept("one", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 0) is RuntimeArchiveExtractionPolicy.Decision.Allow)
        assertTrue(policy.accept("two", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 0) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }

    @Test
    fun rejectsFileParentConflicts() {
        val policy = RuntimeArchiveExtractionPolicy()
        policy.accept("rootfs", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1)
        assertTrue(policy.accept("rootfs/usr/env", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }

    @Test
    fun rejectsReplacingExistingDirectoryTreeWithFile() {
        val policy = RuntimeArchiveExtractionPolicy()
        policy.accept("rootfs/usr/env", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1)
        assertTrue(policy.accept("rootfs", RuntimeArchiveExtractionPolicy.EntryKind.FILE, 1) is RuntimeArchiveExtractionPolicy.Decision.Block)
    }
}
