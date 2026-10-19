package com.fm.runnermanager.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RuntimeTarGzExtractorTest {
    @Test
    fun extractsRegularFileAndPreservesExecutableBit() {
        withArchive(listOf(Entry.file("rootfs/usr/bin/env", "env".toByteArray(), executable = true))) { archive, root ->
            val result = RuntimeTarGzExtractor().extract(archive, root)
            assertEquals(1, result.filesWritten)
            assertEquals(3L, result.bytesWritten)
            assertEquals("env", File(root, "rootfs/usr/bin/env").readText())
            assertTrue(File(root, "rootfs/usr/bin/env").canExecute())
        }
    }

    @Test
    fun rejectsTraversalAndLeavesNoPayload() {
        withArchive(listOf(Entry.file("../escape", byteArrayOf(1)))) { archive, root ->
            try {
                RuntimeTarGzExtractor().extract(archive, root)
                throw AssertionError("Traversal entry should be blocked")
            } catch (_: IOException) {
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun mapsAbsoluteGuestSymlinkToRelativeTargetInsideStagedRootfs() {
        withArchive(listOf(Entry.symlink("rootfs/usr/local/bin/tool", "/usr/bin/tool-real"))) { archive, root ->
            RuntimeTarGzExtractor().extract(archive, root)
            val link = File(root, "rootfs/usr/local/bin/tool").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("../../bin/tool-real", Files.readSymbolicLink(link).toString())
        }
    }

    @Test
    fun rejectsRelativeSymlinkThatEscapesArchiveRoot() {
        withArchive(listOf(Entry.symlink("rootfs/etc/bad", "../../../../outside"))) { archive, root ->
            try {
                RuntimeTarGzExtractor().extract(archive, root)
                throw AssertionError("Escaping link should be blocked")
            } catch (_: IOException) {
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun rejectsArchiveWhenTotalDeclaredBytesExceedLimitAndCleansEarlierFiles() {
        withArchive(listOf(
            Entry.file("rootfs/first", ByteArray(4)),
            Entry.file("rootfs/second", ByteArray(4))
        )) { archive, root ->
            try {
                RuntimeTarGzExtractor(maxTotalBytes = 6).extract(archive, root)
                throw AssertionError("Total-size limit should be enforced")
            } catch (_: IOException) {
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun refusesNonEmptyStagingDirectoryWithoutChangingExistingFile() {
        withArchive(listOf(Entry.file("rootfs/new", byteArrayOf(1)))) { archive, root ->
            val existing = File(root, "keep.txt")
            existing.writeText("keep")
            try {
                RuntimeTarGzExtractor().extract(archive, root)
                throw AssertionError("Non-empty staging directory should be refused")
            } catch (_: IllegalArgumentException) {
                assertEquals("keep", existing.readText())
                assertFalse(File(root, "rootfs").exists())
            }
        }
    }

    @Test
    fun rejectsArchiveEntriesThatTraverseAnEarlierSymlink() {
        withArchive(listOf(
            Entry.symlink("rootfs/usr/link", "/etc"),
            Entry.file("rootfs/usr/link/passwd", "blocked".toByteArray())
        )) { archive, root ->
            try {
                RuntimeTarGzExtractor().extract(archive, root)
                throw AssertionError("Entry beneath a symlink should be blocked")
            } catch (_: IOException) {
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun rejectsHardLinks() {
        withArchive(listOf(Entry.hardlink("rootfs/hard", "rootfs/target"))) { archive, root ->
            try {
                RuntimeTarGzExtractor().extract(archive, root)
                throw AssertionError("Hard link should be blocked")
            } catch (_: IOException) {
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    private data class Entry(
        val name: String,
        val data: ByteArray = byteArrayOf(),
        val linkName: String? = null,
        val type: Byte = TarConstants.LF_NORMAL,
        val executable: Boolean = false
    ) {
        companion object {
            fun file(name: String, data: ByteArray, executable: Boolean = false) =
                Entry(name, data, executable = executable)
            fun symlink(name: String, target: String) =
                Entry(name, linkName = target, type = TarConstants.LF_SYMLINK)
            fun hardlink(name: String, target: String) =
                Entry(name, linkName = target, type = TarConstants.LF_LINK)
        }
    }

    private fun withArchive(entries: List<Entry>, test: (File, File) -> Unit) {
        val temp = Files.createTempDirectory("fm-targz-test").toFile()
        val archive = File(temp, "runtime.tar.gz")
        val root = File(temp, "staging")
        assertTrue(root.mkdir())
        GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { item ->
                    val entry = TarArchiveEntry(item.name, item.type).apply {
                        size = item.data.size.toLong()
                        item.linkName?.let { linkName = it }
                        mode = if (item.executable) 0b001001001 else 0b110100100
                    }
                    tar.putArchiveEntry(entry)
                    if (item.type == TarConstants.LF_NORMAL) tar.write(item.data)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        try {
            test(archive, root)
        } finally {
            temp.deleteRecursively()
        }
    }
}
