package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArchivePathValidatorTest {
    @Test
    fun acceptsSafeNestedFilePath() {
        assertEquals(
            RuntimeArchivePathValidator.Result.Safe("rootfs/usr/bin/env"),
            RuntimeArchivePathValidator.validate("rootfs/usr/bin/env")
        )
    }

    @Test
    fun acceptsDirectoryEntryAndNormalizesTrailingSlash() {
        assertEquals(
            RuntimeArchivePathValidator.Result.Safe("rootfs/usr/bin"),
            RuntimeArchivePathValidator.validate("rootfs/usr/bin/")
        )
    }

    @Test
    fun rejectsEmptyAndRootOnlyNames() {
        assertTrue(RuntimeArchivePathValidator.validate("") is RuntimeArchivePathValidator.Result.Unsafe)
        assertTrue(RuntimeArchivePathValidator.validate("/") is RuntimeArchivePathValidator.Result.Unsafe)
    }

    @Test
    fun rejectsAbsolutePaths() {
        assertTrue(RuntimeArchivePathValidator.validate("/etc/passwd") is RuntimeArchivePathValidator.Result.Unsafe)
    }

    @Test
    fun rejectsParentTraversalAtAnyDepth() {
        for (name in listOf("../outside", "rootfs/../outside", "rootfs/usr/../../outside")) {
            assertTrue("$name should be rejected", RuntimeArchivePathValidator.validate(name) is RuntimeArchivePathValidator.Result.Unsafe)
        }
    }

    @Test
    fun rejectsDotSegments() {
        for (name in listOf("./file", "rootfs/./file")) {
            assertTrue("$name should be rejected", RuntimeArchivePathValidator.validate(name) is RuntimeArchivePathValidator.Result.Unsafe)
        }
    }

    @Test
    fun rejectsRepeatedSeparators() {
        assertTrue(RuntimeArchivePathValidator.validate("rootfs//etc") is RuntimeArchivePathValidator.Result.Unsafe)
    }

    @Test
    fun rejectsBackslashesAndDrivePaths() {
        for (name in listOf("rootfs\\\\etc\\\\passwd", "C:/Windows/system.ini")) {
            assertTrue("$name should be rejected", RuntimeArchivePathValidator.validate(name) is RuntimeArchivePathValidator.Result.Unsafe)
        }
    }

    @Test
    fun rejectsControlCharactersAndColonSegments() {
        assertTrue(RuntimeArchivePathValidator.validate("rootfs/fi\u0000le") is RuntimeArchivePathValidator.Result.Unsafe)
        assertTrue(RuntimeArchivePathValidator.validate("rootfs/stream:payload") is RuntimeArchivePathValidator.Result.Unsafe)
    }

    @Test
    fun rejectsNetworkStyleAbsolutePaths() {
        assertTrue(RuntimeArchivePathValidator.validate("//server/share") is RuntimeArchivePathValidator.Result.Unsafe)
    }
}
