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
        val registerBlockers: List<String>,
        val startBlockers: List<String>,
        val stopBlockers: List<String>,
        val onlineBlockers: List<String>
    )

    fun evaluate(facts: RunnerControlFacts): Decision {
        val registerBlockers = mutableListOf<String>()
        if (!facts.accountIdentityVerified) registerBlockers += "GitHub account identity is not verified"
        if (!facts.repositorySelected) registerBlockers += "No repository is selected"
        if (!facts.registrationPermissionVerified) registerBlockers += "Repository registration permission is not verified"
        if (!facts.runtimeInstallPreflightPassed) registerBlockers += "Runtime integrity preflight has not passed"
        if (!facts.runtimeLayoutReady) registerBlockers += "Installed runtime layout is not ready"

        val startBlockers = registerBlockers.toMutableList()
        if (!facts.serviceConnected) startBlockers += "No verified local runner service connection"
        if (facts.processAlive) startBlockers += "Runner process is already alive"

        val stopBlockers = mutableListOf<String>()
        if (!facts.serviceConnected) stopBlockers += "No verified local runner service connection"
        if (!facts.processAlive) stopBlockers += "Local runner process is not confirmed alive"

        val onlineBlockers = mutableListOf<String>()
        if (!facts.serviceConnected) onlineBlockers += "No verified local runner service connection"
        if (!facts.processAlive) onlineBlockers += "Local runner process is not confirmed alive"
        if (!facts.githubRunnerOnline) onlineBlockers += "GitHub has not confirmed this runner online"

        return Decision(
            canRegister = registerBlockers.isEmpty(),
            canStart = startBlockers.isEmpty(),
            canStop = stopBlockers.isEmpty(),
            canReportOnline = onlineBlockers.isEmpty(),
            registerBlockers = registerBlockers.toList(),
            startBlockers = startBlockers.toList(),
            stopBlockers = stopBlockers.toList(),
            onlineBlockers = onlineBlockers.toList()
        )
    }
}
