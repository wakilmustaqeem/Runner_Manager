package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerControlReadinessTest {
    private val allReady = RunnerControlFacts(
        accountIdentityVerified = true,
        repositorySelected = true,
        registrationPermissionVerified = true,
        runtimeInstallPreflightPassed = true,
        runtimeLayoutReady = true,
        serviceConnected = true,
        processAlive = true,
        githubRunnerOnline = true
    )

    @Test
    fun reportsOnlineOnlyWhenServiceProcessAndGitHubAllConfirm() {
        val result = RunnerControlReadiness.evaluate(allReady)
        assertTrue(result.canReportOnline)
        assertTrue(result.canStop)
        assertFalse(result.canStart)
    }

    @Test
    fun blocksRegistrationWithoutVerifiedIdentity() {
        val result = RunnerControlReadiness.evaluate(allReady.copy(accountIdentityVerified = false))
        assertFalse(result.canRegister)
        assertFalse(result.canStart)
        assertTrue(result.registerBlockers.contains("GitHub account identity is not verified"))
    }

    @Test
    fun registrationBlockersDoNotIncludeServiceOrOnlineState() {
        val result = RunnerControlReadiness.evaluate(
            allReady.copy(serviceConnected = false, processAlive = false, githubRunnerOnline = false)
        )
        assertTrue(result.canRegister)
        assertTrue(result.registerBlockers.isEmpty())
        assertFalse(result.startBlockers.isEmpty())
        assertFalse(result.stopBlockers.isEmpty())
        assertFalse(result.onlineBlockers.isEmpty())
    }

    @Test
    fun blocksRegistrationWithoutSelectedRepository() {
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(repositorySelected = false)).canRegister
        )
    }

    @Test
    fun blocksRegistrationWithoutPermission() {
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(registrationPermissionVerified = false)).canRegister
        )
    }

    @Test
    fun blocksRegistrationUntilRuntimeIntegrityAndLayoutPass() {
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(runtimeInstallPreflightPassed = false)).canRegister
        )
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(runtimeLayoutReady = false)).canRegister
        )
    }

    @Test
    fun neverReportsOnlineFromGitHubMetadataAlone() {
        val result = RunnerControlReadiness.evaluate(
            allReady.copy(serviceConnected = false, processAlive = false, githubRunnerOnline = true)
        )
        assertFalse(result.canReportOnline)
    }

    @Test
    fun neverReportsOnlineFromLocalProcessAlone() {
        val result = RunnerControlReadiness.evaluate(
            allReady.copy(githubRunnerOnline = false)
        )
        assertFalse(result.canReportOnline)
    }

    @Test
    fun startRequiresConnectedServiceAndNoExistingProcess() {
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(serviceConnected = false, processAlive = false)).canStart
        )
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(processAlive = true)).canStart
        )
        assertTrue(
            RunnerControlReadiness.evaluate(allReady.copy(processAlive = false)).canStart
        )
    }

    @Test
    fun stopRequiresConfirmedLocalProcessAndService() {
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(processAlive = false)).canStop
        )
        assertFalse(
            RunnerControlReadiness.evaluate(allReady.copy(serviceConnected = false)).canStop
        )
    }
}
