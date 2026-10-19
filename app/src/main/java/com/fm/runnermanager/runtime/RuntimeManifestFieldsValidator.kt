/*
 * FM Runner Manager runtime manifest field validation.
 *
 * This validates already-decoded fields only. It does not parse JSON, fetch
 * manifests, verify signatures, download archives, or authorize installation.
 * Callers must require a valid signature AND this validation before proceeding.
 */
package com.fm.runnermanager.runtime

import java.net.URI
import java.util.Locale

data class RuntimeManifestFields(
    val version: String,
    val archiveUrl: String,
    val sha256: String
)

object RuntimeManifestFieldsValidator {
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun validate(fields: RuntimeManifestFields): Result {
        val version = fields.version
        if (version != version.trim() || !versionPattern.matches(version)) {
            return Result.Invalid("Runtime version is blank or has an unsupported format")
        }

        val digest = fields.sha256
        if (!sha256Pattern.matches(digest)) {
            return Result.Invalid("Archive SHA-256 must contain exactly 64 hexadecimal characters")
        }

        val rawUrl = fields.archiveUrl
        if (rawUrl.isBlank() || rawUrl != rawUrl.trim()) {
            return Result.Invalid("Archive URL is blank or contains surrounding whitespace")
        }

        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return Result.Invalid("Archive URL is malformed")

        if (!"https".equals(uri.scheme, ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null
        ) {
            return Result.Invalid("Archive URL must be HTTPS with a host and no user-info or fragment")
        }

        return Result.Valid
    }
}
