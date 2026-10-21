package com.fm.runnermanager.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerActivationGateTest {
    private fun readyEvidence() = RunnerActivationGate.Evidence(
        explicitUserConfirmation = true,
        accountLogin = "wakilmustaqeem",
        expectedLogin = "WAKILMUSTAQEEM",
        repositoryOwner = "wakilmustaqeem",
        repositoryName = "Runner_Manager",
        repositoryVerified = true,
        runtimeVerified = true,
        runtimeLauncherReady = true,
        registrationGrantAvailable = true,
    )

    private fun assertRepositoryBlocked(evidence: RunnerActivationGate.Evidence) {
        val decision = RunnerActivationGate.evaluate(evidence)
        assertFalse(decision.allowed)
        assertTrue(
            RunnerActivationGate.Blocker.REPOSITORY_NOT_VERIFIED in decision.blockers
        )
    }

    @Test
    fun completeEvidenceAllowsActivation() {
        val decision = RunnerActivationGate.evaluate(readyEvidence())

        assertTrue(decision.allowed)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun missingRuntimeAndConfirmationBlockActivation() {
        val decision = RunnerActivationGate.evaluate(
            readyEvidence().copy(
                explicitUserConfirmation = false,
                runtimeVerified = false,
                runtimeLauncherReady = false,
            )
        )

        assertFalse(decision.allowed)
        assertEquals(
            setOf(
                RunnerActivationGate.Blocker.USER_CONFIRMATION_REQUIRED,
                RunnerActivationGate.Blocker.RUNTIME_NOT_VERIFIED,
                RunnerActivationGate.Blocker.RUNTIME_LAUNCHER_NOT_READY,
            ),
            decision.blockers,
        )
    }

    @Test
    fun accountMismatchBlocksActivation() {
        val decision = RunnerActivationGate.evaluate(
            readyEvidence().copy(accountLogin = "fmmustaqeem")
        )

        assertFalse(decision.allowed)
        assertTrue(
            RunnerActivationGate.Blocker.ACCOUNT_NOT_VERIFIED in decision.blockers
        )
    }

    @Test
    fun malformedRepositoryPathSegmentsBlockActivation() {
        assertRepositoryBlocked(readyEvidence().copy(repositoryName = "owner/repository"))
        assertRepositoryBlocked(readyEvidence().copy(repositoryOwner = ""))
        assertRepositoryBlocked(readyEvidence().copy(repositoryName = ""))
        assertRepositoryBlocked(readyEvidence().copy(repositoryOwner = "."))
        assertRepositoryBlocked(readyEvidence().copy(repositoryName = ".."))
        assertRepositoryBlocked(readyEvidence().copy(repositoryName = "."))
        assertRepositoryBlocked(readyEvidence().copy(repositoryOwner = "owner/name"))
    }

    @Test
    fun overlongGitHubOwnerAndRepositoryNamesBlockActivation() {
        assertRepositoryBlocked(
            readyEvidence().copy(repositoryOwner = "a".repeat(40))
        )
        assertRepositoryBlocked(
            readyEvidence().copy(repositoryName = "r".repeat(101))
        )
    }

    @Test
    fun missingRepositoryAndGrantBlockActivation() {
        val decision = RunnerActivationGate.evaluate(
            readyEvidence().copy(
                repositoryName = null,
                repositoryVerified = false,
                registrationGrantAvailable = false,
            )
        )

        assertFalse(decision.allowed)
        assertTrue(
            RunnerActivationGate.Blocker.REPOSITORY_NOT_VERIFIED in decision.blockers
        )
        assertTrue(
            RunnerActivationGate.Blocker.REGISTRATION_GRANT_NOT_AVAILABLE in decision.blockers
        )
    }
}
