package com.fm.runnermanager.runner

/**
 * Pure safety gate for the eventual UI/service integration.
 *
 * This class does not request GitHub credentials, register a runner, or launch a process.
 * Callers must provide fresh evidence for the selected account/repository and installed
 * runtime. Missing evidence always blocks activation.
 */
object RunnerActivationGate {
    enum class Blocker {
        USER_CONFIRMATION_REQUIRED,
        ACCOUNT_NOT_VERIFIED,
        REPOSITORY_NOT_VERIFIED,
        RUNTIME_NOT_VERIFIED,
        RUNTIME_LAUNCHER_NOT_READY,
        REGISTRATION_GRANT_NOT_AVAILABLE,
    }

    data class Evidence(
        val explicitUserConfirmation: Boolean,
        val accountLogin: String?,
        val expectedLogin: String?,
        val repositoryOwner: String?,
        val repositoryName: String?,
        val repositoryVerified: Boolean,
        val runtimeVerified: Boolean,
        val runtimeLauncherReady: Boolean,
        val registrationGrantAvailable: Boolean,
    )

    data class Decision(
        val allowed: Boolean,
        val blockers: Set<Blocker>,
    )

    fun evaluate(evidence: Evidence): Decision {
        val blockers = linkedSetOf<Blocker>()

        if (!evidence.explicitUserConfirmation) {
            blockers += Blocker.USER_CONFIRMATION_REQUIRED
        }

        val account = evidence.accountLogin?.trim()
        val expected = evidence.expectedLogin?.trim()
        if (account.isNullOrEmpty() || expected.isNullOrEmpty() ||
            !account.equals(expected, ignoreCase = true)
        ) {
            blockers += Blocker.ACCOUNT_NOT_VERIFIED
        }

        if (evidence.repositoryOwner.isNullOrBlank() ||
            evidence.repositoryName.isNullOrBlank() ||
            !evidence.repositoryVerified
        ) {
            blockers += Blocker.REPOSITORY_NOT_VERIFIED
        }

        if (!evidence.runtimeVerified) {
            blockers += Blocker.RUNTIME_NOT_VERIFIED
        }

        if (!evidence.runtimeLauncherReady) {
            blockers += Blocker.RUNTIME_LAUNCHER_NOT_READY
        }

        if (!evidence.registrationGrantAvailable) {
            blockers += Blocker.REGISTRATION_GRANT_NOT_AVAILABLE
        }

        return Decision(allowed = blockers.isEmpty(), blockers = blockers)
    }
}
