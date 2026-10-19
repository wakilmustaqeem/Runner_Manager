/*
 * Runtime archive digest verification helper.
 *
 * Prefer the InputStream overload for real archives: it hashes in bounded
 * memory and does not close the caller-owned stream. This helper does not
 * download, extract, or install anything.
 */
package com.fm.runnermanager.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

object RuntimeArchiveDigestVerifier {
    sealed interface Result {
        data object Match : Result
        data object InvalidExpectedDigest : Result
        data object Mismatch : Result
        data class ReadFailure(val message: String) : Result
    }

    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun verify(archiveBytes: ByteArray, expectedSha256: String): Result =
        verify(ByteArrayInputStream(archiveBytes), expectedSha256)

    /**
     * Hashes [archiveStream] in bounded memory. The caller retains ownership
     * of the stream and must close it. A read failure is not treated as a match.
     */
    fun verify(archiveStream: InputStream, expectedSha256: String): Result {
        if (!sha256Pattern.matches(expectedSha256)) {
            return Result.InvalidExpectedDigest
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val count = archiveStream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        } catch (e: IOException) {
            return Result.ReadFailure(e.message ?: "Unable to read runtime archive")
        }

        val actual = digest.digest()
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            Result.Match
        } else {
            Result.Mismatch
        }
    }
}
