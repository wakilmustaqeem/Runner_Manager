package com.fm.runnermanager.runtime

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and authenticates a runtime archive into app-private staging.
 *
 * Manifest JSON is parsed only after its detached signature has been verified.
 * This class deliberately does not extract or activate the archive and does not
 * register/start a GitHub Actions runner.
 *
 * Manifest schema (signed exact UTF-8 bytes):
 * {
 *   "schemaVersion": 1,
 *   "version": 1,
 *   "expiresAtEpochSeconds": 1790000000,
 *   "archiveUrl": "https://...",
 *   "archiveBytes": 123456,
 *   "archiveSha256": "<64 hex chars>"
 * }
 */
object RuntimeArtifactStager {
    data class StagedArtifact(
        val version: Long,
        val file: File,
        val sha256: String,
        val archiveBytes: Long,
    )

    fun stage(
        manifestUrl: String,
        detachedSignatureBase64: String,
        trustedPublicKeyBase64: String,
        stagingDir: File,
        minimumAllowedVersion: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        maxManifestBytes: Int = 256 * 1024,
        maxArchiveBytes: Long = 2L * 1024L * 1024L * 1024L,
    ): StagedArtifact {
        requireHttps(manifestUrl)
        require(minimumAllowedVersion >= 0L) { "Invalid rollback floor" }
        require(maxManifestBytes in 1..(1024 * 1024)) { "Invalid manifest size limit" }
        require(maxArchiveBytes > 0L) { "Invalid archive size limit" }

        // Establish the staging boundary before any network access. A symlinked
        // staging path could otherwise redirect downloaded runtime bytes outside
        // app-private storage.
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw IOException("Unable to create app-private staging directory")
        }
        if (!stagingDir.isDirectory || java.nio.file.Files.isSymbolicLink(stagingDir.toPath())) {
            throw SecurityException("Runtime staging path must be a real directory")
        }
        val canonicalStaging = stagingDir.canonicalFile
        val canonicalParent = stagingDir.absoluteFile.parentFile?.canonicalFile
            ?: throw SecurityException("Runtime staging directory has no parent")
        if (canonicalStaging.parentFile != canonicalParent) {
            throw SecurityException("Runtime staging directory must remain a direct child of its parent")
        }

        val manifestBytes = fetchBytes(manifestUrl, maxManifestBytes.toLong())
        if (!RuntimeManifestSignature.verify(
                manifestBytes,
                detachedSignatureBase64,
                trustedPublicKeyBase64,
            )
        ) throw SecurityException("Runtime manifest signature verification failed")

        // Signature has been verified over the exact received bytes before parsing.
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val manifest = JSONObject(manifestText)
        if (manifest.optInt("schemaVersion", -1) != 1) {
            throw SecurityException("Unsupported runtime manifest schema")
        }
        val version = manifest.optLong("version", -1L)
        val expires = manifest.optLong("expiresAtEpochSeconds", -1L)
        val archiveBytes = manifest.optLong("archiveBytes", -1L)
        val archiveUrl = manifest.optString("archiveUrl", "")
        val expectedSha256 = manifest.optString("archiveSha256", "")

        if (version <= 0L || version < minimumAllowedVersion) {
            throw SecurityException("Runtime version rejected by anti-rollback policy")
        }
        if (expires <= nowEpochSeconds) throw SecurityException("Runtime manifest expired")
        if (archiveBytes <= 0L || archiveBytes > maxArchiveBytes) {
            throw SecurityException("Runtime archive size is outside the allowed range")
        }
        if (!expectedSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            throw SecurityException("Invalid runtime archive SHA-256")
        }
        requireHttps(archiveUrl)

        val requiredSpace = if (archiveBytes <= Long.MAX_VALUE / 3L) archiveBytes * 3L else Long.MAX_VALUE
        if (stagingDir.usableSpace < requiredSpace) {
            throw IOException("Insufficient staging storage for safe download and recovery")
        }

        val ready = File(stagingDir, "runtime-$version-${expectedSha256.lowercase().take(12)}.archive")
        if (java.nio.file.Files.isSymbolicLink(ready.toPath())) {
            throw SecurityException("Staged archive destination must not be a symbolic link")
        }
        if (ready.exists()) {
            if (ready.length() == archiveBytes && RuntimePreflight.matchesSha256(ready, expectedSha256)) {
                return StagedArtifact(version, ready, expectedSha256.lowercase(), archiveBytes)
            }
            throw SecurityException("Existing staged archive failed integrity verification")
        }
        val partial = File.createTempFile("runtime-$version-", ".part", stagingDir)

        try {
            downloadExact(archiveUrl, partial, archiveBytes, maxArchiveBytes)
            val actual = RuntimePreflight.sha256(partial)
            if (!MessageDigest.isEqual(
                    actual.lowercase().toByteArray(Charsets.US_ASCII),
                    expectedSha256.lowercase().toByteArray(Charsets.US_ASCII),
                )
            ) throw SecurityException("Runtime archive SHA-256 mismatch")
            if (!partial.renameTo(ready)) throw IOException("Unable to finalize staged archive")
            return StagedArtifact(version, ready, actual, archiveBytes)
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private fun requireHttps(value: String) {
        val url = URL(value)
        require(url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()) {
            "Only absolute HTTPS URLs are allowed"
        }
        require(url.userInfo == null) { "Credentials embedded in URLs are forbidden" }
    }

    private fun fetchBytes(url: String, limit: Long): ByteArray {
        val connection = openHttps(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > limit) throw IOException("Response exceeds configured size limit")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IOException("Response exceeds configured size limit")
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadExact(url: String, destination: File, expectedBytes: Long, maxBytes: Long) {
        if (expectedBytes > maxBytes) throw IOException("Archive exceeds configured size limit")
        val connection = openHttps(url)
        try {
            val declared = connection.contentLengthLong
            if (declared >= 0L && declared != expectedBytes) {
                throw IOException("Archive Content-Length does not match signed manifest")
            }
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > expectedBytes || total > maxBytes) {
                            throw IOException("Archive exceeded signed size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    if (total != expectedBytes) throw IOException("Archive size does not match signed manifest")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(value: String): HttpURLConnection {
        requireHttps(value)
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val status = connection.responseCode
            connection.disconnect()
            throw IOException("HTTPS request failed with status $status")
        }
        return connection
    }
}
