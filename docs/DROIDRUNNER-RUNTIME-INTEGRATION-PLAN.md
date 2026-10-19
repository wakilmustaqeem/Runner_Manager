# FM Runner Manager — real runner runtime integration

**Status:** Stage 1 source audit documented. Stage 2 integration plan only. Runtime integration and Stage 3 testing have not started.

## Contents
- [Core invariant](#core-invariant)
- [Audited architecture constraint](#audited-architecture-constraint)
- [State model](#state-model)
- [Adapter contract](#adapter-contract)
- [Readiness gate](#readiness-gate)
- [Failure states and UI](#failure-states-and-ui)
- [Rollback and kill switch](#rollback-and-kill-switch)
- [GPL-2.0-only compliance gate](#gpl-20-only-compliance-gate)
- [Test plan — Stage 3, not started](#test-plan--stage-3-not-started)
- [Separate blocker tickets](#separate-blocker-tickets)
- [Acceptance criteria](#acceptance-criteria)

## Core invariant

**Bound ≠ Online ≠ Job-Verified**

The app must distinguish three facts:
1. The control interface is connected and responsive.
2. GitHub confirms the registered runner is online.
3. At least one synthetic test job has completed successfully and its result has been verified.

READY is an app-level acceptance state. It must not be inferred from service start, binding, registration-token issuance, or GitHub online status alone. The runner may reach ONLINE_IDLE after local health and GitHub online checks pass; the overall integration is not READY until the synthetic-job gate passes.

## Audited architecture constraint

The upstream DroidRunner source audit found:
- RunnerService is a foreground Android service with listener/process supervision and restart/backoff behavior.
- RunnerService.onBind() currently returns null; a supported external Binder/AIDL control API has not been verified.
- RuntimeInstaller checks bundle SHA-256 and supports detached ECDSA P-256 manifest signatures, but publisher verification requires a trusted signing key to be configured.
- The inspected source did not reveal a paid-license activation or expiry system. This is not proof about every dependency or external service.
- The upstream project is GPL-2.0-only.

Do not implement a fake Binder client or assume another app can control DroidRunner as-is. Before runtime code, choose and document one architecture: (A) adapt/port the required runtime into FM Runner Manager, or (B) implement and security-review an explicit cross-app IPC contract in the runtime provider. Do not use accessibility automation or undocumented internal intents as a substitute for a supported control API.

Upstream references:
- [RunnerService.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerService.kt)
- [RuntimeInstaller.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runtime/RuntimeInstaller.kt)
- [RunnerRegistration.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerRegistration.kt)
- [Architecture documentation](https://github.com/m96-chan/DroidRunner/blob/main/docs/ARCHITECTURE.md)
- [GPL-2.0-only licence](https://github.com/m96-chan/DroidRunner/blob/main/LICENSE)

## State model

    NOT_INSTALLED
        | runtime installed and integrity/publisher checks pass
        v
    BIND_FAILED  <---- control timeout or service crash
        | supported control channel healthy
        v
    DEGRADED     <---- stale registration, lost heartbeat, health/network uncertainty
        | local health + registration valid
        v
    REGISTERED
        | GitHub-side online status confirmed
        v
    ONLINE_IDLE
        | job accepted
        v
    BUSY
        | job finishes and result is verified
        v
    ONLINE_IDLE

Integration acceptance: ONLINE_IDLE + verified synthetic-job result -> READY.

State transitions must be based on observed facts, not button taps or requested operations. Prohibited transitions:
- NOT_INSTALLED -> READY because configuration fields are present.
- BIND_FAILED -> ONLINE_IDLE without successful health and registration checks.
- BUSY -> READY without verifying the completed job result.
- Any state -> LICENSE_EXPIRED unless a real, authoritative license-validation mechanism is implemented and tested.

LICENSE_EXPIRED remains inactive/not shown unless a separate investigation establishes that a genuine license system exists.

## Adapter contract

Target contract only; this is not implemented code. Types may be refined during implementation.

    sealed interface RunnerState {
        data object NotInstalled : RunnerState
        data class BindFailed(val reason: String) : RunnerState
        data class Degraded(val reason: String, val lastHeartbeatEpochMs: Long?) : RunnerState
        data class Registered(val runnerId: String) : RunnerState
        data class OnlineIdle(val runnerId: String, val lastHeartbeatEpochMs: Long) : RunnerState
        data class Busy(val runnerId: String, val jobId: String) : RunnerState
    }

    interface LocalRunnerAdapter {
        fun observeState(): kotlinx.coroutines.flow.Flow<RunnerState>
        suspend fun start(): StartResult
        suspend fun stop(): StopResult
        suspend fun healthCheck(): HealthSnapshot
    }

    sealed interface StartResult {
        data object ControlConnected : StartResult
        data class Registered(val runnerId: String) : StartResult
        data class Ready(val runnerId: String, val syntheticJobId: String) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    sealed interface StopResult {
        data object Stopped : StopResult
        data class StillRunning(val reason: String) : StopResult
        data class Failed(val reason: String) : StopResult
    }

Required semantics:
- ControlConnected means only that the chosen supported control channel is connected; it does not mean READY.
- Ready may be returned only after local health is good, GitHub confirms the runner online, and the synthetic-job result is verified.
- stop() reports Stopped only after process/service termination is independently confirmed. Calling stopService() is not sufficient evidence.
- observeState() must emit regressions such as ONLINE_IDLE -> DEGRADED; states are not assumed monotonic.
- Health snapshots must include evidence timestamps and distinguish unknown/stale data from healthy data.
- Never include access tokens, registration tokens, or secrets in state, errors, UI, logs, clipboard, or audit events.

## Readiness gate

Keep existing account, exact repository, permission, runtime, and process checks. Add these evidence-based gates:

| Gate | Evidence | Failure state |
|---|---|---|
| runtimePackageInstalled | Runtime presence and verified artifact integrity; publisher signature verification where supported | NOT_INSTALLED or verification failure |
| controlInterfaceBound | Successful connection to the explicitly selected supported control interface; proposed initial timeout: 5 seconds | BIND_FAILED |
| healthFresh | Runtime health check and heartbeat younger than 60 seconds; tune only with test evidence | DEGRADED |
| registrationValid | Exact target repository and runner identity verified; credential freshness validated without exposing secrets | DEGRADED |
| githubOnlineConfirmed | GitHub-side runner status confirms online for the intended repository | REGISTERED or DEGRADED |
| syntheticJobPassed | Synthetic job accepted, completed, and expected result verified | Integration remains not ready |

READY requires every required gate to be explicitly true. Null, unknown, stale, or contradictory results fail closed. GitHub online confirmation alone permits at most ONLINE_IDLE, not overall integration READY.

The 60-second heartbeat freshness and 5-second control timeout are initial policy values, not upstream guarantees; validate them on the target device before treating them as final.

## Failure states and UI

| State | User-facing message | Recovery action |
|---|---|---|
| NOT_INSTALLED | “DroidRunner/runtime نصب نہیں یا تصدیق نہیں ہوئی” | Show approved install/verification instructions |
| BIND_FAILED | “کنٹرول رابطہ ناکام” | Retry the supported control connection; show a safe diagnostic |
| DEGRADED | “Runner کی حالت غیر یقینی” | Show last heartbeat age and non-secret reason; offer recheck |
| REGISTERED | “Runner رجسٹرڈ ہے؛ online تصدیق باقی ہے” | Recheck GitHub status and local health |
| ONLINE_IDLE | “Runner online اور خالی ہے؛ synthetic test باقی ہے” | Offer the explicitly approved synthetic test |
| BUSY | “Job چل رہا ہے” | Show job ID and observed state; do not start another job |
| LICENSE_EXPIRED | Inactive placeholder; do not display yet | Revisit only after a real license-validation system is confirmed |

Never label an unverified or mock state as active, online, or ready.

## Rollback and kill switch

- Feature flag: runner.runtime.integration.enabled, default false.
- With the flag enabled, the full readiness gate still applies; the synthetic-job result is mandatory for integration acceptance.
- With the flag disabled, disable the new integration path. Do not restore an old mock/false-positive path that reported a runner active without evidence.
- A kill switch should disable new job starts and surface diagnostics if repeated BUSY -> DEGRADED transitions or service crashes exceed a defined, test-backed threshold. Do not silently stop or discard an active job.
- Keep a documented manual disable path and record only non-secret reason codes and timestamps.
- Do not enable this flag for FM Commerce until synthetic test and gate tests pass.

## GPL-2.0-only compliance gate

DroidRunner declares GPL-2.0-only. Before copying/adapting code or distributing an integrated APK:
1. Preserve upstream copyright and licence notices in copied/adapted files.
2. Track modifications and provide corresponding source and required licence materials for distributed covered code.
3. Determine whether the chosen architecture creates a derivative/combined work. An out-of-process protocol and code linking may have different implications; do not assume an adapter interface settles the legal question.
4. Obtain legal review of distribution obligations and licence compatibility. If the resulting covered work must be licensed under GPL-2.0-only, plan Runner Manager distribution accordingly.
5. Include notices for bundled runtime, root filesystem, and other third-party components as required by their licences.

This is a release blocker and separate review ticket. This plan is not legal advice and does not itself establish licence compliance.

## Test plan — Stage 3, not started

No Stage 3 tests are authorized by this document alone. When separately approved, use a dedicated test repository and one job at a time.
- Unit tests: state-machine transitions, unknown/stale evidence, adapter contract fakes, and online-to-degraded regressions.
- Synthetic-only integration test: a harmless echo/heartbeat workflow with no FM Commerce secrets or production operations.
- Negative gate test: syntheticJobPassed false or unknown must never yield integration READY.
- Failure injection: control timeout, service/process death, stale heartbeat, network loss, stale registration, and contradictory GitHub/local status.
- Stop verification: do not report stopped until process termination is observed.
- FM Commerce migration is the final stage, only after all required tests pass and there is separate explicit approval.

## Separate blocker tickets

1. **GitHub auth/session refresh:** investigate repeated reconnects independently. It is not a substitute for implementing the runner runtime. Do not request credentials or ask the user to reconnect as a way to mask this runtime gap.
2. **GPL-2.0-only legal review:** determine derivative-work/distribution obligations before code adaptation or release.
3. **License activation investigation:** no paid-license mechanism was found in inspected DroidRunner files; keep LICENSE_EXPIRED inactive unless further evidence establishes a real validation system.
4. **Runtime installer trust configuration:** ensure trusted signing keys are present in release configuration; verify signature failure is fail-closed. SHA-256 alone checks integrity against the supplied digest but does not independently establish publisher identity.
5. **Control architecture decision:** choose same-app runtime integration or design/review a supported cross-app IPC API. Do not assume the current upstream service can be bound by another app.

## Acceptance criteria

- Work remains on a dedicated feature branch; do not change main, FM Commerce, FTP, or production as part of this planning step.
- This documentation change alone must not trigger an APK build or Actions job.
- No registration-token request or runner registration until the actual runtime can safely consume the token and the exact account/repository are confirmed.
- Do not claim the runner is online unless local runtime evidence and GitHub-side status both confirm it.
- READY requires the verified synthetic-job result.
- One job at a time; first integration job must be harmless and run on a dedicated test repository.
- Keep wakilmustaqeem/Runner_Manager and fmmustaqeem/fm-commerce-platform account contexts separate.
