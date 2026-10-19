package com.fm.runnermanager.adapters

/**
 * Boundary between GitHub repository authorization and a real local Actions runner.
 *
 * This interface intentionally has no fake/default implementation. UI code must treat
 * a missing adapter as UNAVAILABLE and keep registration/start/stop controls disabled.
 * Implement only after the actual DroidRunner IPC or a bundled compatible runtime is
 * verified on the target Android device.
 */
interface LocalRunnerAdapter {
    fun inspect(): RuntimeSnapshot

    /**
     * Register with a short-lived token and pass it directly to the verified runtime.
     * Implementations must not log or return the token, and must fail closed if the
     * selected account/repository differs from the confirmation the user approved.
     */
    fun register(
        owner: String,
        repository: String,
        registrationToken: String
    ): RuntimeOperationResult

    fun start(): RuntimeOperationResult

    fun stop(): RuntimeOperationResult
}

enum class RunnerRuntimeState {
    UNAVAILABLE,
    DISCONNECTED,
    REGISTERED,
    ONLINE_IDLE,
    BUSY,
    ERROR
}

data class RuntimeSnapshot(
    val state: RunnerRuntimeState,
    val runnerName: String? = null,
    val detail: String = "Runtime not verified"
)

data class RuntimeOperationResult(
    val success: Boolean,
    val state: RunnerRuntimeState,
    /** Safe user-facing explanation; never include credentials or raw command output. */
    val message: String
)
