package com.fm.runnermanager.runtime

import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Fail-closed checks used before a future runtime installer is allowed to download
 * or activate an Actions runtime. This class deliberately performs no network or
 * filesystem mutation; it is safe to run as a preflight on the device.
 */
data class RuntimePreflightResult(
    val ready: Boolean,
    val blockers: List<String>,
    val abi: String,
    val freeBytes: Long,
)

object RuntimePreflight {
    const val REQUIRED_FREE_SPACE_MULTIPLIER = 3L

    fun inspect(
        filesDir: File,
        archiveBytes: Long,
        manifestUrl: String,
        trustedSigningKeyConfigured: Boolean,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): RuntimePreflightResult {
        val blockers = mutableListOf<String>()
        val abi = supportedAbis.firstOrNull().orEmpty()
        val free = runCatching { filesDir.usableSpace }.getOrDefault(0L)

        if (abi != "arm64-v8a") blockers += "Only arm64-v8a runtime packaging is currently supported"
        if (!manifestUrl.startsWith("https://")) blockers += "Runtime manifest must use HTTPS"
        if (!trustedSigningKeyConfigured) blockers += "No FM-owned trusted runtime signing key is configured"
        if (archiveBytes <= 0L) blockers += "Manifest must provide a positive archive size before installation"
        val required = if (archiveBytes > 0L && archiveBytes <= Long.MAX_VALUE / REQUIRED_FREE_SPACE_MULTIPLIER) {
            archiveBytes * REQUIRED_FREE_SPACE_MULTIPLIER
        } else {
            Long.MAX_VALUE
        }
        if (free < required) blockers += "Insufficient app-private storage for download, extraction, and recovery"

        return RuntimePreflightResult(
            ready = blockers.isEmpty(),
            blockers = blockers,
            abi = abi,
            freeBytes = free,
        )
    }

    /** SHA-256 helper for a downloaded archive; caller must compare against a signed manifest. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun matchesSha256(file: File, expectedHex: String): Boolean {
        if (!expectedHex.matches(Regex("^[0-9a-fA-F]{64}$"))) return false
        return runCatching { sha256(file).equals(expectedHex, ignoreCase = true) }.getOrDefault(false)
    }
}
