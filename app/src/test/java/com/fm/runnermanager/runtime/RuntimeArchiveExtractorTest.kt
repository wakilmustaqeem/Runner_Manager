package com.fm.runnermanager.runtime

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArchiveExtractorTest {
    @Test
    fun extractsSafeFileUnderStagingRootAndReportsActualBytes() {
        val parent = Files.createTempDirectory("fm-extract-test").toFile()
        val archive = File(parent, "runtime.zip")
        val stage = File(parent, "stage").apply { mkdir() }
        try {
            writeZip(archive, listOf("rootfs/usr/bin/env" to "safe bytes".toByteArray()))
            val result = RuntimeArchiveExtractor().extract(archive, stage)
            assertEquals(RuntimeArchiveExtractor.Result.Extracted(1, 10), result)
            assertEquals("safe bytes", File(stage, "rootfs/usr/bin/env").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndLeavesNoExtractedPayload() {
        val parent = Files.createTempDirectory("fm-extract-test").toFile()
        val archive = File(parent, "runtime.zip")
        val stage = File(parent, "stage").apply { mkdir() }
        try {
            writeZip(archive, listOf("../outside.txt" to "nope".toByteArray()))
            val result = RuntimeArchiveExtractor().extract(archive, stage)
            assertTrue(result is RuntimeArchiveExtractor.Result.Blocked)
            assertTrue(stage.list().orEmpty().isEmpty())
            assertTrue(!File(parent, "outside.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun refusesNonEmptyStagingDirectory() {
        val parent = Files.createTempDirectory("fm-extract-test").toFile()
        val archive = File(parent, "runtime.zip")
        val stage = File(parent, "stage").apply { mkdir() }
        File(stage, "keep.txt").writeText("keep")
        try {
            writeZip(archive, listOf("new.txt" to byteArrayOf(1)))
            assertTrue(RuntimeArchiveExtractor().extract(archive, stage) is RuntimeArchiveExtractor.Result.Blocked)
            assertEquals("keep", File(stage, "keep.txt").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsDeclaredOversizedEntryBeforeWritingIt() {
        val parent = Files.createTempDirectory("fm-extract-test").toFile()
        val archive = File(parent, "runtime.zip")
        val stage = File(parent, "stage").apply { mkdir() }
        try {
            writeZip(archive, listOf("large.bin" to ByteArray(16) { 1 }))
            val result = RuntimeArchiveExtractor(maxEntryBytes = 8).extract(archive, stage)
            assertTrue(result is RuntimeArchiveExtractor.Result.Blocked)
            assertTrue(stage.list().orEmpty().isEmpty())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymlinkStagingRoot() {
        val parent = Files.createTempDirectory("fm-extract-test").toFile()
        val archive = File(parent, "runtime.zip")
        val realStage = File(parent, "real-stage").apply { mkdir() }
        val linkStage = File(parent, "linked-stage")
        try {
            writeZip(archive, listOf("file.txt" to byteArrayOf(1)))
            try {
                Files.createSymbolicLink(linkStage.toPath(), realStage.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.io.IOException) {
                return
            } catch (_: SecurityException) {
                return
            }
            assertTrue(RuntimeArchiveExtractor().extract(archive, linkStage) is RuntimeArchiveExtractor.Result.Blocked)
            assertTrue(realStage.list().orEmpty().isEmpty())
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
