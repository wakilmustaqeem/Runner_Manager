package com.fm.runnermanager.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
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
    fun hashesInputStreamInBoundedChunks() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Match,
            RuntimeArchiveDigestVerifier.verify(ByteArrayInputStream(archive), sha256(archive))
        )
    }

    @Test
    fun rejectsMismatchingDigest() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.Mismatch,
            RuntimeArchiveDigestVerifier.verify(archive, "0".repeat(64))
        )
    }

    @Test
    fun rejectsMalformedExpectedDigestBeforeReadingStream() {
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.InvalidExpectedDigest,
            RuntimeArchiveDigestVerifier.verify(archive, "not-a-sha256")
        )
    }

    @Test
    fun reportsReadFailureInsteadOfTreatingItAsVerified() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("simulated read failure")
        }
        val result = RuntimeArchiveDigestVerifier.verify(broken, "a".repeat(64))
        assertEquals(
            RuntimeArchiveDigestVerifier.Result.ReadFailure("simulated read failure"),
            result
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
