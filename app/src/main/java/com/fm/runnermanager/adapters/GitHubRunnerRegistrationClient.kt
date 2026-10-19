package com.fm.runnermanager.adapters

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Requests a short-lived GitHub Actions repository runner registration token.
 *
 * This is only the GitHub-side token request; it does NOT install, register, start,
 * or execute a runner. A verified local runner runtime must consume the token directly.
 *
 * Required authorization depends on credential type:
 * - Fine-grained PAT / GitHub App installation or user token: repository Administration: write.
 * - Classic OAuth token: the broad repo scope may authorize this endpoint.
 *
 * Never display, persist, copy, or log the returned token. Call only after an explicit
 * user confirmation and after checking the selected repository and effective permissions.
 */
class GitHubRunnerRegistrationClient(
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 15_000
) {
    data class RegistrationToken(
        val token: String,
        val expiresAt: String
    )

    fun requestRepositoryRegistrationToken(
        accessToken: String,
        owner: String,
        repository: String
    ): RegistrationToken {
        require(accessToken.isNotBlank()) { "GitHub authorization required" }
        requireSafePathPart(owner)
        requireSafePathPart(repository)

        val connection = (URL(
            "https://api.github.com/repos/$owner/$repository/actions/runners/registration-token"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "FM-Runner-Manager-Android")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        try {
            connection.outputStream.use { /* Empty POST body by API design. */ }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()

            if (status !in 200..299) {
                // Do not include response body, headers, or credentials in thrown errors.
                val hint = when (status) {
                    401 -> "GitHub authorization is missing or expired"
                    403 -> "GitHub denied runner registration-token permission"
                    404 -> "Repository not found or access is not granted"
                    422 -> "GitHub rejected the registration-token request"
                    else -> "GitHub registration-token request failed (HTTP $status)"
                }
                throw IllegalStateException(hint)
            }

            val json = JSONObject(response)
            val token = json.optString("token").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("GitHub returned no registration token")
            val expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("GitHub returned no token expiry")
            return RegistrationToken(token, expiresAt)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSafePathPart(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_.-]+")) && value != "." && value != "..") {
            "Invalid repository path"
        }
    }
}
