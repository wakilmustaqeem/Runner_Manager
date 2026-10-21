package com.fm.runnermanager.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRuntimeExtractorTest {
    @Test fun extractsRegularFilesAndDirectoriesWithinRoot() {
        val root = createTempDir(prefix = "fm-safe-extract-")
        try {
            val entries = sequenceOf(
                SafeRuntimeExtractor.Entry("rootfs", SafeRuntimeExtractor.EntryType.DIRECTORY),
                SafeRuntimeExtractor.Entry(
                    "rootfs/usr/bin/runner",
                    SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                    declaredBytes = 6L,
                    openContent = { ByteArrayInputStream("runner".toByteArray()) },
                ),
            )
            val result = SafeRuntimeExtractor.extract(root, entries)
            assertEquals(2, result.entriesWritten)
            assertEquals(6L, result.bytesWritten)
            assertEquals("runner", File(root, "rootfs/usr/bin/runner").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAbsolutePathsAndLinkEntries() {
        val root = createTempDir(prefix = "fm-safe-extract-")
        try {
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(root, sequenceOf(
                    SafeRuntimeExtractor.Entry("../outside", SafeRuntimeExtractor.EntryType.DIRECTORY)
                ))
            }.isSuccess)
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(root, sequenceOf(
                    SafeRuntimeExtractor.Entry("/etc/passwd", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(1)) })
                ))
            }.isSuccess)
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(root, sequenceOf(
                    SafeRuntimeExtractor.Entry("rootfs/link", SafeRuntimeExtractor.EntryType.SYMBOLIC_LINK)
                ))
            }.isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsDeclaredSizeMismatchAndDuplicateOverwrite() {
        val root = createTempDir(prefix = "fm-safe-extract-")
        try {
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(root, sequenceOf(
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        5L, { ByteArrayInputStream("tiny".toByteArray()) })
                ))
            }.isSuccess)
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(root, sequenceOf(
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(1)) }),
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(2)) }),
                ))
            }.isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }
}
