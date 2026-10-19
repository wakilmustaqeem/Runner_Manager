package com.fm.runnermanager.adapters

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Minimal GitHub OAuth device-flow + read-only REST client foundation.
 *
 * Security boundaries:
 * - Caller must obtain the Client ID from the user's registered OAuth App.
 * - Never pass a client secret or PAT; device flow uses a public Client ID.
 * - Call every method off the Android main thread.
 * - Access tokens are accepted only as method arguments and are never persisted or logged here.
 * - Keep token storage/session handling outside this class and use a reviewed encrypted vault.
 * - This class intentionally exposes no write/dispatch/runner registration endpoints.
 */
class GitHubDeviceFlowClient(
    private val clientId: String,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 15_000
) {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long
    )

    data class TokenPoll(
        val accessToken: String? = null,
        val error: String? = null,
        val intervalSeconds: Long? = null
    )

    data class Identity(val login: String, val name: String?)
    data class Repository(
        val owner: String,
        val name: String,
        val isPrivate: Boolean,
        val htmlUrl: String
    )
    data class WorkflowRun(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String?,
        val htmlUrl: String,
        val updatedAt: String
    )
    data class Runner(
        val id: Long,
        val name: String,
        val status: String,
        val busy: Boolean,
        val labels: List<String>
    )

    init {
        require(clientId.isNotBlank()) { "GitHub OAuth App Client ID is required" }
        require(!clientId.contains("\n") && !clientId.contains("\r")) { "Invalid Client ID" }
    }

    /** Start OAuth device authorization. Do not call on the UI thread. */
    fun requestDeviceCode(): DeviceCode {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to clientId,
                // Private-repository access requires the broad OAuth "repo" scope.
                // The UI must explain this before starting authorization; do not treat it as read-only.
                "scope" to "repo read:user"
            ),
            acceptJson = true
        )
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 0L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(1L)
        )
    }

    /**
     * Poll once only; caller controls delay/retries using intervalSeconds and must stop
     * after expiry. Typical errors include authorization_pending, slow_down, expired_token,
     * access_denied and incorrect_client_credentials.
     */
    fun pollDeviceToken(deviceCode: String): TokenPoll {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            ),
            acceptJson = true
        )
        val token = json.optString("access_token").takeIf { it.isNotBlank() }
        return TokenPoll(
            accessToken = token,
            error = json.optString("error").takeIf { it.isNotBlank() },
            intervalSeconds = json.optLong("interval", 0L).takeIf { it > 0L }
        )
    }

    fun identity(accessToken: String): Identity {
        val json = getJson("https://api.github.com/user", accessToken)
        return Identity(json.getString("login"), json.optString("name").takeIf { it.isNotBlank() })
    }

    /** Lists repositories visible to the authorized identity; paginated to a bounded maximum. */
    fun repositories(accessToken: String, maxPages: Int = 10): List<Repository> {
        require(maxPages in 1..10) { "maxPages must be between 1 and 10" }
        val result = mutableListOf<Repository>()
        for (page in 1..maxPages) {
            val array = getArray(
                "https://api.github.com/user/repos?per_page=100&page=$page&sort=updated",
                accessToken
            )
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val owner = item.optJSONObject("owner")?.optString("login").orEmpty()
                val name = item.optString("name")
                if (owner.isNotBlank() && name.isNotBlank()) {
                    result += Repository(
                        owner = owner,
                        name = name,
                        isPrivate = item.optBoolean("private", false),
                        htmlUrl = item.optString("html_url")
                    )
                }
            }
            if (array.length() < 100) break
        }
        return result
    }

    fun workflowRuns(accessToken: String, owner: String, repo: String): List<WorkflowRun> {
        requireSafePathPart(owner)
        requireSafePathPart(repo)
        val array = getJson(
            "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=30",
            accessToken
        ).optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            WorkflowRun(
                id = item.optLong("id"),
                name = item.optString("name", item.optString("display_title")),
                status = item.optString("status", "unknown"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                htmlUrl = item.optString("html_url"),
                updatedAt = item.optString("updated_at")
            )
        }
    }

    fun runners(accessToken: String, owner: String, repo: String): List<Runner> {
        requireSafePathPart(owner)
        requireSafePathPart(repo)
        val array = getJson(
            "https://api.github.com/repos/$owner/$repo/actions/runners?per_page=100",
            accessToken
        ).optJSONArray("runners") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val labelsArray = item.optJSONArray("labels") ?: JSONArray()
            Runner(
                id = item.optLong("id"),
                name = item.optString("name"),
                status = item.optString("status", "unknown"),
                busy = item.optBoolean("busy", false),
                labels = (0 until labelsArray.length()).mapNotNull { j ->
                    labelsArray.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }
                }
            )
        }
    }

    private fun requireSafePathPart(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_.-]+")) && value != "." && value != "..") {
            "Invalid repository path"
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        require(token.isNotBlank()) { "Authorization required" }
        return request(url, "GET", null, token, acceptJson = true).let(::JSONObject)
    }

    private fun getArray(url: String, token: String): JSONArray {
        require(token.isNotBlank()) { "Authorization required" }
        return request(url, "GET", null, token, acceptJson = true).let(::JSONArray)
    }

    private fun postForm(url: String, values: Map<String, String>, acceptJson: Boolean): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
        return request(url, "POST", body, null, acceptJson).let(::JSONObject)
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        token: String?,
        acceptJson: Boolean
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", if (acceptJson) "application/json" else "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "FM-Runner-Manager-Android")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                // Deliberately omit response body and headers to avoid leaking sensitive data.
                throw IllegalStateException("GitHub request failed (HTTP $code)")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
