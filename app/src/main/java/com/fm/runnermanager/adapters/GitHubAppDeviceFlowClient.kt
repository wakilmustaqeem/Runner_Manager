package com.fm.runnermanager.adapters

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GitHub App user-device-flow client, separate from the legacy classic OAuth client.
 *
 * A GitHub App's permissions come from its configured permissions and installation;
 * they are not granted by asking for classic OAuth scopes. This client deliberately
 * sends no `repo` scope. It only performs authorization and identity checks; it does
 * not register, start, stop, or dispatch an Actions runner.
 *
 * Call from a background thread. Keep access/refresh tokens in a reviewed encrypted
 * vault, never in logs, clipboard, UI text, or persistent plain-text preferences.
 */
class GitHubAppDeviceFlowClient(
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

    data class UserToken(
        val accessToken: String,
        val expiresInSeconds: Long?,
        val refreshToken: String?,
        val refreshTokenExpiresInSeconds: Long?,
        val scopes: String?
    )

    data class PollResult(
        val token: UserToken? = null,
        val error: String? = null,
        val intervalSeconds: Long? = null
    )

    data class Identity(val login: String, val name: String?)
    data class Installation(
        val id: Long,
        val account: String,
        val accountType: String,
        val appSlug: String,
        val repositorySelection: String,
        val administrationPermission: String?
    )
    data class Repository(
        val owner: String,
        val name: String,
        val isPrivate: Boolean,
        val htmlUrl: String
    ) {
        val fullName: String get() = "$owner/$name"
    }

    init {
        require(clientId.isNotBlank()) { "GitHub App Client ID is required" }
        require(!clientId.contains("\n") && !clientId.contains("\r")) { "Invalid Client ID" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Timeouts must be positive" }
    }

    /** Begin GitHub App device authorization. The caller displays the code to the user. */
    fun requestDeviceCode(): DeviceCode {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to clientId,
                // GitHub App only: requests rotating access/refresh tokens, not repository scopes.
                "scope" to "offline_access"
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

    /** Poll once; caller owns delay, slow_down handling, cancellation, and expiry. */
    fun pollDeviceToken(deviceCode: String): PollResult {
        require(deviceCode.isNotBlank()) { "Device authorization is missing" }
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            ),
            acceptJson = true
        )
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        return if (access != null) {
            PollResult(token = json.toUserToken(access))
        } else {
            PollResult(
                error = json.optString("error").takeIf { it.isNotBlank() } ?: "authorization_failed",
                intervalSeconds = json.optLong("interval", 0L).takeIf { it > 0L }
            )
        }
    }

    /** Renew an expiring GitHub App user token; the caller must securely replace stored tokens. */
    fun refreshUserToken(refreshToken: String): PollResult {
        require(refreshToken.isNotBlank()) { "Refresh token is missing" }
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            ),
            acceptJson = true
        )
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        return if (access != null) {
            PollResult(token = json.toUserToken(access))
        } else {
            PollResult(error = json.optString("error").takeIf { it.isNotBlank() } ?: "refresh_failed")
        }
    }

    fun identity(accessToken: String): Identity {
        require(accessToken.isNotBlank()) { "Access token is missing" }
        val json = getJson("https://api.github.com/user", accessToken)
        return Identity(
            login = json.getString("login"),
            name = json.optString("name").takeIf { it.isNotBlank() }
        )
    }

    /** List installations of this GitHub App visible to the authenticated user. */
    fun installations(accessToken: String, maxPages: Int = 10): List<Installation> {
        require(maxPages in 1..10) { "maxPages must be between 1 and 10" }
        val result = mutableListOf<Installation>()
        for (page in 1..maxPages) {
            val json = getJson(
                "https://api.github.com/user/installations?per_page=100&page=$page",
                accessToken
            )
            val items = json.optJSONArray("installations") ?: break
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val account = item.optJSONObject("account")
                val permissions = item.optJSONObject("permissions")
                result += Installation(
                    id = item.optLong("id", -1L),
                    account = account?.optString("login").orEmpty(),
                    accountType = account?.optString("type").orEmpty(),
                    appSlug = item.optString("app_slug"),
                    repositorySelection = item.optString("repository_selection"),
                    administrationPermission = permissions
                        ?.optString("administration")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                )
            }
            if (items.length() < 100) break
        }
        return result.filter { it.id > 0L && it.account.isNotBlank() }
    }

    /** List only repositories the selected installation grants to this GitHub App. */
    fun installationRepositories(
        accessToken: String,
        installationId: Long,
        maxPages: Int = 10
    ): List<Repository> {
        require(installationId > 0L) { "Invalid installation ID" }
        require(maxPages in 1..10) { "maxPages must be between 1 and 10" }
        val result = mutableListOf<Repository>()
        for (page in 1..maxPages) {
            val json = getJson(
                "https://api.github.com/user/installations/$installationId/repositories?per_page=100&page=$page",
                accessToken
            )
            val items = json.optJSONArray("repositories") ?: break
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
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
            if (items.length() < 100) break
        }
        return result
    }

    private fun JSONObject.toUserToken(access: String) = UserToken(
        accessToken = access,
        expiresInSeconds = optLong("expires_in", -1L).takeIf { it >= 0L },
        refreshToken = optString("refresh_token").takeIf { it.isNotBlank() },
        refreshTokenExpiresInSeconds = optLong("refresh_token_expires_in", -1L).takeIf { it >= 0L },
        scopes = optString("scope").takeIf { it.isNotBlank() }
    )

    private fun postForm(
        endpoint: String,
        values: Map<String, String>,
        acceptJson: Boolean
    ): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Accept", if (acceptJson) "application/json" else "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "FM-Runner-Manager")
        }
        return try {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String, accessToken: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "FM-Runner-Manager")
        }
        return try {
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            // Do not include response bodies: they can contain provider details and are not
            // needed for the user-facing error. Keep status for diagnostics without secrets.
            throw IllegalStateException("GitHub authorization request failed (HTTP $status)")
        }
        return JSONObject(text)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
