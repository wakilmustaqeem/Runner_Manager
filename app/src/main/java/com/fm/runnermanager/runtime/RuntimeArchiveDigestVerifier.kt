/*
 * Runtime archive digest verification helper.
 *
 * Call this only after a runtime archive has been downloaded to a controlled
 * staging location. This pure helper does not download, extract, or install.
 */
package com.fm.runnermanager.runtime

import java.security.MessageDigest

object RuntimeArchiveDigestVerifier {
    sealed interface Result {
        data object Match : Result
        data object InvalidExpectedDigest : Result
        data object Mismatch : Result
    }

    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun verify(archiveBytes: ByteArray, expectedSha256: String): Result {
        if (!sha256Pattern.matches(expectedSha256)) {
            return Result.InvalidExpectedDigest
        }

        val actual = MessageDigest.getInstance("SHA-256")
            .digest(archiveBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            Result.Match
        } else {
            Result.Mismatch
        }
    }
}
