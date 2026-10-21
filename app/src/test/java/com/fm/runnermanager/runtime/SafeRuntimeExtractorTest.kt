package com.fm.runnermanager.runtime

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRuntimeExtractorTest {
    @Test fun extractsRegularFilesAndDirectoriesWithinRoot() {
        val parent = createTempDir(prefix = "fm-safe-extract-")
        val destination = File(parent, "published")
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
            val result = SafeRuntimeExtractor.extract(destination, entries)
            assertEquals(2, result.entriesWritten)
            assertEquals(6L, result.bytesWritten)
            assertEquals("runner", File(destination, "rootfs/usr/bin/runner").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun rejectsUnsafeEntriesWithoutPublishingPartialOutput() {
        val parent = createTempDir(prefix = "fm-safe-extract-")
        val destination = File(parent, "published")
        try {
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(destination, sequenceOf(
                    SafeRuntimeExtractor.Entry("safe/file", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        4L, { ByteArrayInputStream("data".toByteArray()) }),
                    SafeRuntimeExtractor.Entry("../outside", SafeRuntimeExtractor.EntryType.DIRECTORY),
                ))
            }.isSuccess)
            assertFalse(destination.exists())
            assertFalse(File(parent, "outside").exists())

            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(destination, sequenceOf(
                    SafeRuntimeExtractor.Entry("/etc/passwd", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(1)) })
                ))
            }.isSuccess)
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(destination, sequenceOf(
                    SafeRuntimeExtractor.Entry("rootfs/link", SafeRuntimeExtractor.EntryType.SYMBOLIC_LINK)
                ))
            }.isSuccess)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun rejectsDeclaredSizeMismatchAndDuplicateOverwrite() {
        val parent = createTempDir(prefix = "fm-safe-extract-")
        try {
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(File(parent, "mismatch"), sequenceOf(
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        5L, { ByteArrayInputStream("tiny".toByteArray()) })
                ))
            }.isSuccess)
            assertFalse(File(parent, "mismatch").exists())

            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(File(parent, "duplicate"), sequenceOf(
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(1)) }),
                    SafeRuntimeExtractor.Entry("a", SafeRuntimeExtractor.EntryType.REGULAR_FILE,
                        1L, { ByteArrayInputStream(byteArrayOf(2)) }),
                ))
            }.isSuccess)
            assertFalse(File(parent, "duplicate").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun refusesToOverwriteExistingDestination() {
        val parent = createTempDir(prefix = "fm-safe-extract-")
        val destination = File(parent, "published")
        try {
            assertTrue(destination.mkdir())
            File(destination, "keep.txt").writeText("keep")
            assertFalse(runCatching {
                SafeRuntimeExtractor.extract(destination, emptySequence())
            }.isSuccess)
            assertEquals("keep", File(destination, "keep.txt").readText())
        } finally {
            parent.deleteRecursively()
        }
    }
}
