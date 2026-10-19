package com.fm.runnermanager.runtime

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeArchiveDigestVerifierTest {
    private val archive = "runtime archive test bytes".toByteArray(Charsets.UTF_8)

    @Test
    fun acceptsMatchingSha256() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Match,
            RuntimeArchiveDigestVerifier.verify(archive, sha256(archive))
        )
    }

    @Test
    fun acceptsUppercaseHexDigest() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Match,
            RuntimeArchiveDigestVerifier.verify(archive, sha256(archive).uppercase())
        )
    }

    @Test
    fun rejectsMismatchingDigest() {
        val wrong = "0".repeat(64)
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Mismatch,
            RuntimeArchiveDigestVerifier.verify(archive, wrong)
        )
    }

    @Test
    fun rejectsMalformedExpectedDigest() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.InvalidExpectedDigest,
            RuntimeArchiveDigestVerifier.verify(archive, "not-a-sha256")
        )
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.InvalidExpectedDigest,
            RuntimeArchiveDigestVerifier.verify(archive, "a".repeat(63))
        )
    }

    @Test
    fun rejectsEmptyArchiveUnlessDigestActuallyMatchesEmptyBytes() {
        val empty = byteArrayOf()
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Mismatch,
            RuntimeArchiveDigestVerifier.verify(empty, sha256(archive))
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
