package com.fm.runnermanager.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArchiveDestinationResolverTest {
    @Test
    fun resolvesSafePathInsideStagingRoot() {
        val root = Files.createTempDirectory("fm-runtime-stage").toFile()
        try {
            val result = RuntimeArchiveDestinationResolver.resolve(root, "rootfs/usr/bin/env")
            assertTrue(result is RuntimeArchiveDestinationResolver.Result.Resolved)
            assertEquals(
                File(root, "rootfs/usr/bin/env").canonicalFile,
                (result as RuntimeArchiveDestinationResolver.Result.Resolved).destination
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalBeforeResolvingDestination() {
        val root = Files.createTempDirectory("fm-runtime-stage").toFile()
        try {
            assertTrue(
                RuntimeArchiveDestinationResolver.resolve(root, "../outside")
                    is RuntimeArchiveDestinationResolver.Result.Blocked
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blocksExistingSymlinkThatEscapesStagingRoot() {
        val parent = Files.createTempDirectory("fm-runtime-parent").toFile()
        val root = File(parent, "stage").apply { mkdir() }
        val outside = File(parent, "outside").apply { mkdir() }
        try {
            val link = File(root, "escape")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.io.IOException) {
                return
            } catch (_: SecurityException) {
                return
            }

            assertTrue(
                RuntimeArchiveDestinationResolver.resolve(root, "escape/payload")
                    is RuntimeArchiveDestinationResolver.Result.Blocked
            )
        } finally {
            parent.deleteRecursively()
        }
    }
}
