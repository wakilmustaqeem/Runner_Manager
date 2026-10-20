# Independent FM Runner Manager Architecture

Status: implementation plan; this document does not enable runner execution, registration, workflow dispatch, package installation, or Start/Stop controls.

## Non-negotiable boundaries

- FM Runner Manager is a standalone Android application with its own implementation and lifecycle.
- DroidRunner is a separate application and is reference material for learning only. FM Runner Manager must not call, control, embed, depend on, or integrate with DroidRunner or its process/service.
- Do not copy DroidRunner source code into this project. Reuse general engineering ideas only; independently implement and document FM code and review applicable licenses before redistributing any third-party material.
- Preserve the existing GitHub account separation. Only one GitHub account may be active in the app at a time; other configured accounts remain inactive until explicitly selected.
- Default deny: no runner registration, job execution, workflow dispatch, or Start/Stop action until its implementation and tests are explicitly approved.
- Read-only GitHub metadata must remain separate from local runtime control.

## Proposed independent components

1. `GitHubReadOnlyAdapter`
   - Read authenticated identity, accessible repositories, workflow-run metadata, and runner metadata only where GitHub permissions allow.
   - Use bounded pagination, timeouts, rate-limit handling, explicit refresh, and stale-data timestamps.
   - Do not dispatch workflows or change repository settings.

2. `CredentialVault`
   - Initial Android Keystore-backed AES-GCM token vault is implemented in `app/src/main/java/com/fm/runnermanager/security/CredentialVault.kt`.
   - Activity integration stores tokens encrypted at rest and keeps only the selected account token in memory; on restart the selected saved session is verified against GitHub before being treated as connected.
   - This implementation has not yet been built or verified on-device. Token revocation/expiry can still require reauthorization, and key invalidation must be handled through safe recovery.
   - Store no credentials until the user explicitly completes authorization.
   - Use Android Keystore-backed encryption for persisted credential material and document token expiry/revocation behavior.
   - Never place tokens, device codes, or authorization headers in logs, diagnostics, clipboard reports, screenshots, source code, or backups.
   - On account switch, deactivate the prior account before activating the selected account. Never fall back to another account's token or repositories.
   - Disconnect must clear local credentials and session metadata. Do not promise permanent login: revoked or expired credentials require reauthorization.

3. `IndependentRunnerRuntime`
   - A future FM-owned runtime and Android foreground service; no IPC, intent, shared process, or other integration with DroidRunner.
   - Prove Android ARM64/runtime compatibility before attempting registration. Device architecture alone is not proof that the official GitHub Actions runner can execute correctly.
   - Keep registration, removal, and job execution disabled until a documented runtime implementation exists and is verified on-device.
   - Never expose registration tokens. Registration/removal require explicit confirmation, bounded token lifetime handling, and a recovery note.

4. `RunnerPolicyGate`
   - Enforce charging/mains-power preference, minimum battery, thermal threshold, and minimum free storage at the actual job-admission boundary, not only in the UI.
   - Fail closed on invalid or unavailable readings when the relevant policy requires them.
   - Re-check conditions before job admission and during execution where safe cancellation is supported.
   - Until runtime enforcement and tests pass, label policy values “saved locally — not enforced”.

5. `AuditLog`
   - Record metadata-only security events such as connect, disconnect, account switch, denied admission, and runtime state changes.
   - Redact secrets by construction; avoid copying raw request/response bodies into logs.
   - Keep account/repository context explicit without storing access tokens.

## Implementation phases and gates

### Phase 0 — Safe baseline
- Keep the current dashboard usable offline.
- Create test doubles and unit tests; do not add live runner controls.
- Maintain a restore point and work only on a feature branch.

### Phase 1 — Session correctness
- Verify device authorization and identity display on the device.
- Implement/test one-active-account semantics, encrypted credential storage, disconnect, account switching, expiry/revocation, process recreation, and redacted audit events.
- Do not claim persistent login until device tests prove it.

### Phase 2 — Read-only GitHub data
- Test repository isolation, private repository visibility, pagination limits, permission denial, offline behavior, rate limits, API errors, and stale-data display.
- Confirm read-only mode cannot dispatch workflows or mutate repository settings.

### Phase 3 — Runtime feasibility
- Independently research and verify the official runner's compatibility with the target Android/ARM64 environment.
- Document sandbox limitations, filesystem boundaries, process permissions, cleanup, and recovery behavior.
- No registration or production repository access during feasibility tests.

### Phase 4 — Controlled test execution
- Only after compatibility evidence and separate approval, use a dedicated private test repository and one harmless job at a time.
- Verify runner pickup, logs, resource limits, stop/recovery behavior, workspace cleanup, and that no unrelated repository/account is affected.
- Keep production repositories and valuable secrets out of initial tests.

### Phase 5 — Release gate
- Require passing automated tests, on-device evidence, secret-redaction review, documented limitations, and explicit release approval.
- No automatic release or production changes from this plan alone.

## Acceptance criteria

- No credential persistence or network activity before explicit user authorization.
- Exactly one active GitHub account at a time; inactive account data cannot leak into the selected account's view or actions.
- FM Runner Manager has no runtime dependency on or control interface to DroidRunner.
- Read-only mode cannot dispatch workflows, register/remove runners, or alter repository settings.
- Start/Stop remains disabled until the independent FM runtime is implemented and verified.
- Policy settings are not represented as enforced until checks execute at job admission and tests prove the behavior.
- Tests cover permission denial, account switching, disconnect, token expiry/revocation, process recreation, offline state, API errors, rate limits, stale data, policy boundary values, and secret redaction.

## Security and cost notes

Self-hosted runners execute workflow code on the device and can expose local data if untrusted workflows run. Initial tests must use a dedicated private test repository, least privilege, no valuable secrets, separate work directories, and carefully verified cleanup. Android process isolation or a userspace runtime must not be described as a strong VM/container security boundary without evidence.

A self-hosted runner may reduce eligible GitHub-hosted runner-minute usage, but it does not bypass GitHub plan limits, repository/organization policies, or other service charges. Mobile execution also has reliability, thermal, battery, storage, network, and compatibility trade-offs.
