package com.fm.runnermanager.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeArchiveInstallerTest {
    @Test fun installsVerifiedArchiveAndPublishesOnlyCompleteRootfs() {
        withArtifact("rootfs/usr/bin/runner", "runner".toByteArray()) { artifact, parent ->
            val destination = File(parent, "runtime-v1")
            val installed = RuntimeArchiveInstaller.install(artifact, destination)
            assertTrue(destination.isDirectory)
            assertTrue(installed.rootfsDirectory.isDirectory)
            assertEquals("runner", File(installed.rootfsDirectory, "usr/bin/runner").readText())
            assertEquals(6L, installed.bytesWritten)
        }
    }

    @Test fun rejectsDigestMismatchWithoutPublishing() {
        withArtifact("rootfs/usr/bin/runner", "runner".toByteArray()) { artifact, parent ->
            val tampered = File(parent, "tampered.tar.gz")
            artifact.file.copyTo(tampered)
            tampered.appendBytes(byteArrayOf(0x01))
            val changed = artifact.copy(file = tampered, archiveBytes = tampered.length())
            val destination = File(parent, "runtime-bad")
            assertFalse(runCatching { RuntimeArchiveInstaller.install(changed, destination) }.isSuccess)
            assertFalse(destination.exists())
        }
    }

    @Test fun rejectsArchiveWithoutRootfsAndCleansStaging() {
        withArtifact("not-rootfs/runner", "runner".toByteArray()) { artifact, parent ->
            val destination = File(parent, "runtime-no-rootfs")
            assertFalse(runCatching { RuntimeArchiveInstaller.install(artifact, destination) }.isSuccess)
            assertFalse(destination.exists())
            assertTrue(parent.listFiles().orEmpty().none { it.name.startsWith("fm-runtime-install-") })
        }
    }

    @Test fun refusesToOverwriteExistingRuntime() {
        withArtifact("rootfs/usr/bin/runner", "runner".toByteArray()) { artifact, parent ->
            val destination = File(parent, "runtime-existing")
            assertTrue(destination.mkdir())
            File(destination, "keep.txt").writeText("keep")
            assertFalse(runCatching { RuntimeArchiveInstaller.install(artifact, destination) }.isSuccess)
            assertEquals("keep", File(destination, "keep.txt").readText())
        }
    }

    private fun withArtifact(path: String, bytes: ByteArray, block: (RuntimeArtifactStager.StagedArtifact, File) -> Unit) {
        val parent = Files.createTempDirectory("fm-runtime-installer-test-").toFile()
        val archive = File(parent, "runtime.tar.gz")
        GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val entry = TarArchiveEntry(path).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        val artifact = RuntimeArtifactStager.StagedArtifact(
            version = 1L,
            file = archive,
            sha256 = RuntimePreflight.sha256(archive),
            archiveBytes = archive.length(),
        )
        try {
            block(artifact, parent)
        } finally {
            parent.deleteRecursively()
        }
    }
}
