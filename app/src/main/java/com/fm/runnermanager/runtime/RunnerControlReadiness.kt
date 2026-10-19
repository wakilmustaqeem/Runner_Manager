/*
 * Fail-closed readiness decision for future runner controls.
 *
 * This is a pure policy contract, not a process adapter or service. Callers must
 * supply fresh, independently observed facts; UI labels and cached metadata alone
 * must never be used as evidence that the runner is online.
 */
package com.fm.runnermanager.runtime

data class RunnerControlFacts(
    val accountIdentityVerified: Boolean,
    val repositorySelected: Boolean,
    val registrationPermissionVerified: Boolean,
    val runtimeInstallPreflightPassed: Boolean,
    val runtimeLayoutReady: Boolean,
    val serviceConnected: Boolean,
    val processAlive: Boolean,
    val githubRunnerOnline: Boolean
)

object RunnerControlReadiness {
    data class Decision(
        val canRegister: Boolean,
        val canStart: Boolean,
        val canStop: Boolean,
        val canReportOnline: Boolean,
        val blockers: List<String>
    )

    fun evaluate(facts: RunnerControlFacts): Decision {
        val blockers = mutableListOf<String>()
        if (!facts.accountIdentityVerified) blockers += "GitHub account identity is not verified"
        if (!facts.repositorySelected) blockers += "No repository is selected"
        if (!facts.registrationPermissionVerified) blockers += "Repository registration permission is not verified"
        if (!facts.runtimeInstallPreflightPassed) blockers += "Runtime integrity preflight has not passed"
        if (!facts.runtimeLayoutReady) blockers += "Installed runtime layout is not ready"

        val canRegister = facts.accountIdentityVerified &&
            facts.repositorySelected &&
            facts.registrationPermissionVerified &&
            facts.runtimeInstallPreflightPassed &&
            facts.runtimeLayoutReady

        val canStart = canRegister && facts.serviceConnected && !facts.processAlive
        val canStop = facts.serviceConnected && facts.processAlive
        val canReportOnline = facts.serviceConnected &&
            facts.processAlive &&
            facts.githubRunnerOnline

        if (!facts.serviceConnected) blockers += "No verified local runner service connection"
        if (!facts.processAlive) blockers += "Local runner process is not confirmed alive"
        if (!facts.githubRunnerOnline) blockers += "GitHub has not confirmed this runner online"

        return Decision(
            canRegister = canRegister,
            canStart = canStart,
            canStop = canStop,
            canReportOnline = canReportOnline,
            blockers = blockers.distinct()
        )
    }
}
