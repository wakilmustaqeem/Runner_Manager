package com.fm.runnermanager.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstalledRuntimeStoreTest {
    @Test fun installsThenPublishesPointerToVerifiedRootfs() {
        withArtifact { artifact, root ->
            val active = InstalledRuntimeStore.installAndActivate(root, artifact)
            assertEquals(1L, active.version)
            assertTrue(active.directory(root).isDirectory)
            assertTrue(active.rootfs(root).isDirectory)
            assertEquals(active, InstalledRuntimeStore.readActive(root))
            assertTrue(File(root, "active-installed-runtime.json").isFile)
        }
    }

    @Test fun refusesPreexistingDirectoryAndDoesNotCreateActivePointer() {
        withArtifact { artifact, root ->
            val name = "runtime-${artifact.version}-${artifact.sha256.take(12)}"
            val existing = File(root, name)
            assertTrue(existing.mkdir())
            File(existing, "rootfs").mkdir()
            assertFalse(runCatching { InstalledRuntimeStore.installAndActivate(root, artifact) }.isSuccess)
            assertNull(InstalledRuntimeStore.readActive(root))
            assertFalse(File(root, "active-installed-runtime.json").exists())
        }
    }

    @Test fun activePointerBecomesInvalidWhenInstalledRootfsDisappears() {
        withArtifact { artifact, root ->
            val active = InstalledRuntimeStore.installAndActivate(root, artifact)
            assertTrue(active.rootfs(root).deleteRecursively())
            assertNull(InstalledRuntimeStore.readActive(root))
        }
    }

    private fun withArtifact(block: (RuntimeArtifactStager.StagedArtifact, File) -> Unit) {
        val root = Files.createTempDirectory("fm-installed-runtime-test-").toFile()
        val archive = File(root, "runtime.tar.gz")
        GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val bytes = "runner".toByteArray()
                val entry = TarArchiveEntry("rootfs/usr/bin/runner").apply { size = bytes.size.toLong() }
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
            block(artifact, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
