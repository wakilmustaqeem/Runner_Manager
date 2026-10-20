package com.fm.runnermanager.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerLifecycleStateMachineTest {
    private val binding = RunnerBinding(
        accountLogin = "wakilmustaqeem",
        repositoryOwner = "wakilmustaqeem",
        repositoryName = "Runner_Manager"
    )

    private fun guards(
        active: RunnerBinding? = binding,
        requested: RunnerBinding? = binding,
        runtimeReady: Boolean = true,
        policyAllows: Boolean = true,
        noOtherJob: Boolean = true,
        healthConfirmed: Boolean = false,
        recoveryEnabled: Boolean = false,
        retryAvailable: Boolean = false
    ) = RunnerLifecycleGuards(
        activeBinding = active,
        requestedBinding = requested,
        runtimeReadyAndVerified = runtimeReady,
        admissionPolicyAllowsOperation = policyAllows,
        noOtherJobActive = noOtherJob,
        processAndSessionHealthConfirmed = healthConfirmed,
        recoveryEnabled = recoveryEnabled,
        retryBudgetAvailable = retryAvailable
    )

    @Test
    fun registrationIsDeniedWhenAccountRepositoryBindingDiffers() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.UNREGISTERED,
            RunnerLifecycleEvent.REQUEST_REGISTRATION,
            guards(requested = binding.copy(repositoryName = "another-repo"))
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun registrationIsDeniedWithoutVerifiedRuntime() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.UNREGISTERED,
            RunnerLifecycleEvent.REQUEST_REGISTRATION,
            guards(runtimeReady = false)
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun registrationIsDeniedWhenAnotherJobIsActive() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.UNREGISTERED,
            RunnerLifecycleEvent.REQUEST_REGISTRATION,
            guards(noOtherJob = false)
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun startRequestDoesNotClaimRunnerIsRunning() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.REGISTERED_STOPPED,
            RunnerLifecycleEvent.REQUEST_START,
            guards()
        )
        assertEquals(
            RunnerTransitionResult.Accepted(RunnerLifecycleState.START_PENDING),
            result
        )
    }

    @Test
    fun runningRequiresIndependentProcessAndSessionHealthEvidence() {
        val denied = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.START_PENDING,
            RunnerLifecycleEvent.PROCESS_AND_SESSION_HEALTH_CONFIRMED,
            guards(healthConfirmed = false)
        )
        assertTrue(denied is RunnerTransitionResult.Denied)

        val accepted = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.START_PENDING,
            RunnerLifecycleEvent.PROCESS_AND_SESSION_HEALTH_CONFIRMED,
            guards(healthConfirmed = true)
        )
        assertEquals(
            RunnerTransitionResult.Accepted(RunnerLifecycleState.RUNNING_CONFIRMED),
            accepted
        )
    }

    @Test
    fun runningConfirmationIsDeniedIfAnotherJobBecameActive() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.START_PENDING,
            RunnerLifecycleEvent.PROCESS_AND_SESSION_HEALTH_CONFIRMED,
            guards(healthConfirmed = true, noOtherJob = false)
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun runningConfirmationIsDeniedIfAdmissionPolicyChanges() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.START_PENDING,
            RunnerLifecycleEvent.PROCESS_AND_SESSION_HEALTH_CONFIRMED,
            guards(healthConfirmed = true, policyAllows = false)
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun recoveryStopsWhenRetryBudgetIsExhausted() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.RECOVERY_WAIT,
            RunnerLifecycleEvent.RECOVERY_RETRY_ALLOWED,
            guards(recoveryEnabled = true, retryAvailable = false)
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun disconnectCleanupCannotBeDivertedByAuthInvalidation() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.DISCONNECT_PENDING,
            RunnerLifecycleEvent.AUTH_INVALIDATED,
            guards()
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }

    @Test
    fun disconnectCleanupCompletesOnlyOnCleanupConfirmation() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.DISCONNECT_PENDING,
            RunnerLifecycleEvent.DISCONNECT_CLEANUP_CONFIRMED,
            guards()
        )
        assertEquals(
            RunnerTransitionResult.Accepted(RunnerLifecycleState.UNREGISTERED),
            result
        )
    }

    @Test
    fun invalidBlankBindingIsRejected() {
        val result = RunnerLifecycleStateMachine.transition(
            RunnerLifecycleState.UNREGISTERED,
            RunnerLifecycleEvent.REQUEST_REGISTRATION,
            guards(requested = binding.copy(repositoryName = " "))
        )
        assertTrue(result is RunnerTransitionResult.Denied)
    }
}
