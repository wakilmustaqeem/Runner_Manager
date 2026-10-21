package com.fm.runnermanager.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeActivationStoreTest {
    @Test fun activatesOnlyVerifiedArchiveAndCanReadPointer() {
        val root = createTempDir(prefix = "fm-runtime-activation-")
        try {
            val staging = File(root, "staging")
            assertTrue(staging.mkdirs())
            val archive = File(staging, "runtime-1.archive")
            archive.writeText("verified runtime archive")
            val digest = RuntimePreflight.sha256(archive)

            val active = RuntimeActivationStore.activateVerifiedArchive(root, archive, 1L, digest)

            assertEquals(1L, active.version)
            assertEquals(archive.name, RuntimeActivationStore.readActive(root)?.archiveName)
            assertEquals(digest, RuntimeActivationStore.readActive(root)?.sha256)
            assertTrue(File(root, "active-runtime.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesTamperedArchiveAndArchiveOutsidePrivateRoot() {
        val root = createTempDir(prefix = "fm-runtime-activation-")
        val outside = File.createTempFile("fm-runtime-outside-", ".archive")
        try {
            val staging = File(root, "staging")
            assertTrue(staging.mkdirs())
            val archive = File(staging, "runtime.archive")
            archive.writeText("original")
            val digest = RuntimePreflight.sha256(archive)
            archive.writeText("tampered")
            assertFalse(runCatching {
                RuntimeActivationStore.activateVerifiedArchive(root, archive, 1L, digest)
            }.isSuccess)

            val outsideDigest = RuntimePreflight.sha256(outside)
            assertFalse(runCatching {
                RuntimeActivationStore.activateVerifiedArchive(root, outside, 1L, outsideDigest)
            }.isSuccess)
            assertNull(RuntimeActivationStore.readActive(root))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun malformedPointerIsNotReportedAsActive() {
        val root = createTempDir(prefix = "fm-runtime-activation-")
        try {
            assertTrue(root.isDirectory)
            File(root, "active-runtime.json").writeText("{not-json")
            assertNull(RuntimeActivationStore.readActive(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
