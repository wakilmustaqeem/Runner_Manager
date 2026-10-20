package com.fm.runnermanager.runner

/**
 * Pure, deterministic lifecycle model for the future local runner adapter.
 *
 * This model does not start processes, call GitHub, persist state, or enable UI controls.
 * Callers must enforce these decisions at the actual runner/process boundary.
 */
enum class RunnerLifecycleState {
    UNREGISTERED,
    REGISTRATION_PENDING,
    REGISTERED_STOPPED,
    START_PENDING,
    RUNNING_CONFIRMED,
    STOP_PENDING,
    RECOVERY_WAIT,
    REGISTRATION_FAILED,
    RUNTIME_FAILED,
    AUTH_BLOCKED,
    INTEGRITY_BLOCKED,
    DISCONNECT_PENDING
}

enum class RunnerLifecycleEvent {
    REQUEST_REGISTRATION,
    REGISTRATION_SUCCEEDED,
    REGISTRATION_FAILED,
    REQUEST_START,
    PROCESS_AND_SESSION_HEALTH_CONFIRMED,
    START_FAILED,
    REQUEST_STOP,
    PROCESS_STOPPED,
    PROCESS_CRASHED,
    RECOVERY_RETRY_ALLOWED,
    AUTH_INVALIDATED,
    RUNTIME_INTEGRITY_FAILED,
    REQUEST_DISCONNECT,
    DISCONNECT_CLEANUP_CONFIRMED,
    RESET_AFTER_FAILED_REGISTRATION
}

/**
 * Non-secret identity binding. Tokens and other credentials must never be put here.
 */
data class RunnerBinding(
    val accountLogin: String,
    val repositoryOwner: String,
    val repositoryName: String
) {
    fun isValid(): Boolean =
        accountLogin.isNotBlank() &&
            repositoryOwner.isNotBlank() &&
            repositoryName.isNotBlank() &&
            repositoryOwner != "." &&
            repositoryOwner != ".." &&
            repositoryName != "." &&
            repositoryName != ".."
}

/**
 * Conditions that must be checked by the caller before a transition is accepted.
 * This is deliberately evidence supplied by the trusted runtime boundary, not the UI.
 */
data class RunnerLifecycleGuards(
    val activeBinding: RunnerBinding?,
    val requestedBinding: RunnerBinding?,
    val runtimeReadyAndVerified: Boolean,
    val admissionPolicyAllowsOperation: Boolean,
    val noOtherJobActive: Boolean,
    val processAndSessionHealthConfirmed: Boolean = false,
    val recoveryEnabled: Boolean = false,
    val retryBudgetAvailable: Boolean = false
)

sealed class RunnerTransitionResult {
    data class Accepted(val state: RunnerLifecycleState) : RunnerTransitionResult()
    data class Denied(val reason: String) : RunnerTransitionResult()
}

/**
 * Validates one requested transition. It has no side effects and fails closed.
 *
 * A UI action alone cannot produce RUNNING_CONFIRMED: the caller must provide
 * process/session health evidence in a separate event and guard.
 */
object RunnerLifecycleStateMachine {
    fun transition(
        current: RunnerLifecycleState,
        event: RunnerLifecycleEvent,
        guards: RunnerLifecycleGuards
    ): RunnerTransitionResult {
        // Once disconnect cleanup starts, do not let a concurrent auth/runtime event
        // divert the state and strand cleanup before DISCONNECT_CLEANUP_CONFIRMED.
        if (current == RunnerLifecycleState.DISCONNECT_PENDING) {
            return if (event == RunnerLifecycleEvent.DISCONNECT_CLEANUP_CONFIRMED) {
                RunnerTransitionResult.Accepted(RunnerLifecycleState.UNREGISTERED)
            } else {
                RunnerTransitionResult.Denied("Disconnect cleanup is pending")
            }
        }

        if (event == RunnerLifecycleEvent.AUTH_INVALIDATED) {
            return RunnerTransitionResult.Accepted(RunnerLifecycleState.AUTH_BLOCKED)
        }
        if (event == RunnerLifecycleEvent.RUNTIME_INTEGRITY_FAILED) {
            return RunnerTransitionResult.Accepted(RunnerLifecycleState.INTEGRITY_BLOCKED)
        }
        if (event == RunnerLifecycleEvent.REQUEST_DISCONNECT) {
            return when (current) {
                RunnerLifecycleState.UNREGISTERED,
                RunnerLifecycleState.REGISTRATION_FAILED,
                RunnerLifecycleState.REGISTERED_STOPPED,
                RunnerLifecycleState.RUNNING_CONFIRMED,
                RunnerLifecycleState.STOP_PENDING,
                RunnerLifecycleState.START_PENDING,
                RunnerLifecycleState.RECOVERY_WAIT,
                RunnerLifecycleState.REGISTRATION_PENDING,
                RunnerLifecycleState.RUNTIME_FAILED,
                RunnerLifecycleState.AUTH_BLOCKED,
                RunnerLifecycleState.INTEGRITY_BLOCKED ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.DISCONNECT_PENDING)
                RunnerLifecycleState.DISCONNECT_PENDING ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.DISCONNECT_PENDING)
            }
        }

        return when (current) {
            RunnerLifecycleState.UNREGISTERED -> when (event) {
                RunnerLifecycleEvent.REQUEST_REGISTRATION -> {
                    val bindingCheck = validateBinding(guards)
                    if (bindingCheck != null) RunnerTransitionResult.Denied(bindingCheck)
                    else if (!guards.runtimeReadyAndVerified) RunnerTransitionResult.Denied("Verified runtime required")
                    else if (!guards.admissionPolicyAllowsOperation) RunnerTransitionResult.Denied("Admission policy denied")
                    else if (!guards.noOtherJobActive) RunnerTransitionResult.Denied("Another job is active")
                    else RunnerTransitionResult.Accepted(RunnerLifecycleState.REGISTRATION_PENDING)
                }
                else -> denied(current, event)
            }

            RunnerLifecycleState.REGISTRATION_PENDING -> when (event) {
                RunnerLifecycleEvent.REGISTRATION_SUCCEEDED ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.REGISTERED_STOPPED)
                RunnerLifecycleEvent.REGISTRATION_FAILED ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.REGISTRATION_FAILED)
                else -> denied(current, event)
            }

            RunnerLifecycleState.REGISTRATION_FAILED -> when (event) {
                RunnerLifecycleEvent.RESET_AFTER_FAILED_REGISTRATION ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.UNREGISTERED)
                else -> denied(current, event)
            }

            RunnerLifecycleState.REGISTERED_STOPPED -> when (event) {
                RunnerLifecycleEvent.REQUEST_START -> {
                    val bindingCheck = validateBinding(guards)
                    if (bindingCheck != null) RunnerTransitionResult.Denied(bindingCheck)
                    else if (!guards.runtimeReadyAndVerified) RunnerTransitionResult.Denied("Verified runtime required")
                    else if (!guards.admissionPolicyAllowsOperation) RunnerTransitionResult.Denied("Admission policy denied")
                    else if (!guards.noOtherJobActive) RunnerTransitionResult.Denied("Another job is active")
                    else RunnerTransitionResult.Accepted(RunnerLifecycleState.START_PENDING)
                }
                else -> denied(current, event)
            }

            RunnerLifecycleState.START_PENDING -> when (event) {
                RunnerLifecycleEvent.PROCESS_AND_SESSION_HEALTH_CONFIRMED -> {
                    val bindingCheck = validateBinding(guards)
                    if (bindingCheck != null) RunnerTransitionResult.Denied(bindingCheck)
                    else if (!guards.processAndSessionHealthConfirmed) {
                        RunnerTransitionResult.Denied("Verified process and session health evidence required")
                    } else if (!guards.runtimeReadyAndVerified || !guards.admissionPolicyAllowsOperation) {
                        RunnerTransitionResult.Denied("Runtime or admission policy no longer permits start")
                    } else if (!guards.noOtherJobActive) {
                        RunnerTransitionResult.Denied("Another job is active")
                    } else RunnerTransitionResult.Accepted(RunnerLifecycleState.RUNNING_CONFIRMED)
                }
                RunnerLifecycleEvent.START_FAILED ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.RUNTIME_FAILED)
                else -> denied(current, event)
            }

            RunnerLifecycleState.RUNNING_CONFIRMED -> when (event) {
                RunnerLifecycleEvent.REQUEST_STOP ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.STOP_PENDING)
                RunnerLifecycleEvent.PROCESS_CRASHED -> {
                    if (guards.recoveryEnabled && guards.retryBudgetAvailable &&
                        guards.runtimeReadyAndVerified && guards.admissionPolicyAllowsOperation
                    ) RunnerTransitionResult.Accepted(RunnerLifecycleState.RECOVERY_WAIT)
                    else RunnerTransitionResult.Accepted(RunnerLifecycleState.RUNTIME_FAILED)
                }
                else -> denied(current, event)
            }

            RunnerLifecycleState.STOP_PENDING -> when (event) {
                RunnerLifecycleEvent.PROCESS_STOPPED ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.REGISTERED_STOPPED)
                else -> denied(current, event)
            }

            RunnerLifecycleState.RECOVERY_WAIT -> when (event) {
                RunnerLifecycleEvent.RECOVERY_RETRY_ALLOWED -> {
                    val bindingCheck = validateBinding(guards)
                    if (bindingCheck != null) RunnerTransitionResult.Denied(bindingCheck)
                    else if (!guards.recoveryEnabled || !guards.retryBudgetAvailable) {
                        RunnerTransitionResult.Denied("Recovery is disabled or retry budget exhausted")
                    } else if (!guards.runtimeReadyAndVerified || !guards.admissionPolicyAllowsOperation ||
                        !guards.noOtherJobActive
                    ) {
                        RunnerTransitionResult.Denied("Recovery admission policy denied")
                    } else RunnerTransitionResult.Accepted(RunnerLifecycleState.START_PENDING)
                }
                else -> denied(current, event)
            }

            RunnerLifecycleState.RUNTIME_FAILED -> when (event) {
                RunnerLifecycleEvent.REQUEST_STOP ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.STOP_PENDING)
                RunnerLifecycleEvent.RESET_AFTER_FAILED_REGISTRATION ->
                    RunnerTransitionResult.Accepted(RunnerLifecycleState.UNREGISTERED)
                else -> denied(current, event)
            }

            RunnerLifecycleState.AUTH_BLOCKED,
            RunnerLifecycleState.INTEGRITY_BLOCKED,
            RunnerLifecycleState.DISCONNECT_PENDING ->
                RunnerTransitionResult.Denied("Transition is blocked")
        }
    }

    private fun validateBinding(guards: RunnerLifecycleGuards): String? {
        val active = guards.activeBinding
            ?: return "No active account/repository binding"
        val requested = guards.requestedBinding
            ?: return "No requested account/repository binding"
        if (!active.isValid() || !requested.isValid()) return "Invalid account/repository binding"
        if (active != requested) return "Active account/repository does not match requested binding"
        return null
    }

    private fun denied(
        current: RunnerLifecycleState,
        event: RunnerLifecycleEvent
    ): RunnerTransitionResult.Denied =
        RunnerTransitionResult.Denied("Event $event is not allowed from state $current")
}
