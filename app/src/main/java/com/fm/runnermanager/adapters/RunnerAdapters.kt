package com.fm.runnermanager.adapters

/**
 * Explicit adapter boundaries for future integrations.
 *
 * No production adapter is provided here. The dashboard must remain offline and
 * runner controls disabled until implementations are authorized and verified.
 */
sealed class AdapterResult<out T> {
    data class Success<T>(val value: T) : AdapterResult<T>()
    data class Unavailable(val reason: String) : AdapterResult<Nothing>()
    data class Failure(val safeMessage: String) : AdapterResult<Nothing>()
}

data class AuthorizedIdentity(
    val accountSlot: Int,
    val login: String,
    val displayName: String? = null
)

data class RepositorySummary(
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val htmlUrl: String
)

data class WorkflowRunSummary(
    val repositoryFullName: String,
    val runId: Long,
    val title: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val updatedAtIso8601: String
)

data class RunnerSummary(
    val id: Long,
    val name: String,
    val status: String,
    val busy: Boolean,
    val labels: List<String>
)

/**
 * Read-only GitHub metadata boundary. Implementations must not dispatch workflows,
 * mutate repository settings, register runners, or expose credentials in errors.
 */
interface GitHubReadOnlyAdapter {
    fun identity(accountSlot: Int): AdapterResult<AuthorizedIdentity>
    fun repositories(accountSlot: Int): AdapterResult<List<RepositorySummary>>
    fun workflowRuns(accountSlot: Int, repositoryFullName: String): AdapterResult<List<WorkflowRunSummary>>
    fun runners(accountSlot: Int, repositoryFullName: String): AdapterResult<List<RunnerSummary>>
}

/**
 * Local runner process boundary. GitHub's API does not start/stop the local process.
 * Keep UI controls disabled unless a real, documented implementation is available.
 */
interface LocalRunnerAdapter {
    fun status(accountSlot: Int): AdapterResult<String>
    fun start(accountSlot: Int, explicitUserConfirmation: Boolean): AdapterResult<Unit>
    fun stop(accountSlot: Int, explicitUserConfirmation: Boolean): AdapterResult<Unit>
}

/**
 * A policy evaluator is only authoritative when called at the actual job-admission
 * boundary by the runner runtime. UI preferences alone do not enforce these rules.
 */
data class RunnerPolicy(
    val mainsPowerOnly: Boolean,
    val minimumBatteryPercent: Int,
    val maximumTemperatureCelsius: Float?,
    val minimumFreeStorageBytes: Long
)

data class DeviceConditions(
    val isCharging: Boolean,
    val batteryPercent: Int,
    val temperatureCelsius: Float?,
    val freeStorageBytes: Long
)

data class PolicyDecision(
    val allowed: Boolean,
    val reasons: List<String>
)

object PolicyGate {
    fun evaluate(policy: RunnerPolicy, conditions: DeviceConditions): PolicyDecision {
        val reasons = mutableListOf<String>()
        if (policy.mainsPowerOnly && !conditions.isCharging) reasons += "Mains power required"
        if (conditions.batteryPercent < policy.minimumBatteryPercent) reasons += "Battery below minimum"
        val maxTemp = policy.maximumTemperatureCelsius
        val actualTemp = conditions.temperatureCelsius
        if (policy.minimumBatteryPercent !in 0..100) reasons += "Invalid battery threshold"
        if (policy.minimumFreeStorageBytes < 0L) reasons += "Invalid storage threshold"
        if (maxTemp != null) {
            if (!maxTemp.isFinite()) {
                reasons += "Invalid temperature threshold"
            } else if (actualTemp == null || !actualTemp.isFinite()) {
                reasons += "Temperature unavailable; fail closed"
            } else if (actualTemp > maxTemp) {
                reasons += "Temperature above limit"
            }
        }
        if (conditions.batteryPercent !in 0..100) reasons += "Invalid battery reading"
        if (conditions.freeStorageBytes < 0L) reasons += "Invalid storage reading"
        if (conditions.freeStorageBytes < policy.minimumFreeStorageBytes) reasons += "Free storage below minimum"
        return PolicyDecision(allowed = reasons.isEmpty(), reasons = reasons)
    }
}
