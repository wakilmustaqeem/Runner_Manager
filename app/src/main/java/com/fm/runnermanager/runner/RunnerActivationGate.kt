package com.fm.runnermanager.runner

/**
 * Pure safety gate for the eventual UI/service integration.
 *
 * This class does not request GitHub credentials, register a runner, or launch a process.
 * Callers must provide fresh evidence for the selected account/repository and installed
 * runtime. Missing evidence always blocks activation.
 */
object RunnerActivationGate {
    private const val MAX_OWNER_LENGTH = 39
    private const val MAX_REPOSITORY_LENGTH = 100
    private const val MAX_REPOSITORY_PATH_LENGTH = MAX_OWNER_LENGTH + 1 + MAX_REPOSITORY_LENGTH

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

        val owner = evidence.repositoryOwner?.trim()
        val repository = evidence.repositoryName?.trim()
        // Owner and repository are separate GitHub path segments. Validate them
        // before accepting the caller's repository verification flag.
        val validOwner = !owner.isNullOrEmpty() &&
            owner == evidence.repositoryOwner &&
            owner.length <= MAX_OWNER_LENGTH &&
            owner != "." && owner != ".." &&
            owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) &&
            !owner.contains("--")

        val validRepository = !repository.isNullOrEmpty() &&
            repository == evidence.repositoryName &&
            repository.length <= MAX_REPOSITORY_LENGTH &&
            repository != "." && repository != ".." &&
            '/' !in repository && '\\' !in repository &&
            repository.none { it.isWhitespace() || it.isISOControl() }

        val repositoryPathLength = if (validOwner && validRepository) {
            owner!!.length + 1 + repository!!.length
        } else {
            Int.MAX_VALUE
        }

        if (!validOwner || !validRepository ||
            repositoryPathLength > MAX_REPOSITORY_PATH_LENGTH ||
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
