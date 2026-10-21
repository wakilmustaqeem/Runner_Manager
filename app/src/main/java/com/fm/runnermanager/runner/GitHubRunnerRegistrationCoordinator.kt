package com.fm.runnermanager.runner

import com.fm.runnermanager.adapters.GitHubDeviceFlowClient
import com.fm.runnermanager.security.SecureGitHubSessionStore

/**
 * Explicitly gated bridge from an encrypted account session to a repository-scoped
 * registration grant. It only requests the short-lived credential; it does not
 * configure or launch the runner. Do not wire this to a background or automatic path.
 */
class GitHubRunnerRegistrationCoordinator(
    private val sessions: SecureGitHubSessionStore,
    private val clientFactory: (String) -> GitHubDeviceFlowClient = { GitHubDeviceFlowClient(it) },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    /**
     * Holds a registration token only in memory and redacts it from logs/toString.
     * The runtime integration should consume it once and pass it only to the verified
     * runner configuration process. This object must never be persisted.
     */
    class OneShotGrant internal constructor(
        token: String,
        val expiresAt: String?,
    ) {
        private var secret: String? = token

        @Synchronized
        fun consume(): String {
            val value = secret ?: throw IllegalStateException("Registration grant was already consumed")
            secret = null
            return value
        }

        @Synchronized
        fun discard() {
            secret = null
        }

        override fun toString(): String = "OneShotGrant(token=[REDACTED], expiresAt=$expiresAt)"
    }

    /**
     * Must be called only from a foreground user flow after an explicit confirmation
     * that the selected GitHub account has repository administration permission.
     * No network request is made unless both confirmation flags are true.
     */
    fun requestGrant(
        slot: Int,
        clientId: String,
        owner: String,
        repo: String,
        explicitUserConfirmation: Boolean,
        repositoryAdministrationPermissionConfirmed: Boolean,
    ): OneShotGrant {
        check(explicitUserConfirmation) { "Explicit user confirmation is required" }
        check(repositoryAdministrationPermissionConfirmed) {
            "Repository administration permission must be confirmed before requesting a registration grant"
        }
        require(slot > 0) { "Account slot must be positive" }
        require(clientId.isNotBlank()) { "GitHub App Client ID is required" }
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && owner != "." && owner != "..") {
            "Invalid repository owner"
        }
        require(repo.matches(Regex("[A-Za-z0-9_.-]+")) && repo != "." && repo != "..") {
            "Invalid repository name"
        }

        val saved = sessions.load(slot)
            ?: throw IllegalStateException("No encrypted GitHub session; reconnect this account")
        val client = clientFactory(clientId)
        val accessToken = validAccessToken(saved, client, slot)
        val identity = client.identity(accessToken)
        require(identity.login.isNotBlank()) { "GitHub identity could not be verified" }

        // This POST only mints a short-lived registration credential. Never log it.
        val grant = client.requestRepositoryRunnerRegistrationToken(accessToken, owner, repo)
        return OneShotGrant(grant.token, grant.expiresAt)
    }

    private fun validAccessToken(
        saved: SecureGitHubSessionStore.Session,
        client: GitHubDeviceFlowClient,
        slot: Int,
    ): String {
        val now = nowEpochSeconds()
        val expiry = saved.expiresAtEpochSeconds
        if (expiry == null || expiry > now + 300L) return saved.accessToken

        val refresh = saved.refreshToken
            ?: if (expiry > now) return saved.accessToken
            else throw IllegalStateException("GitHub access token expired; reconnect this account")

        val result = client.refreshUserToken(refresh)
        val access = result.accessToken
            ?: throw IllegalStateException("GitHub session renewal was refused; reconnect this account")
        val refreshed = SecureGitHubSessionStore.Session(
            accessToken = access,
            refreshToken = result.refreshToken ?: refresh,
            expiresAtEpochSeconds = result.expiresInSeconds?.let { now + it } ?: expiry,
        )
        sessions.save(slot, refreshed)
        return access
    }
}
