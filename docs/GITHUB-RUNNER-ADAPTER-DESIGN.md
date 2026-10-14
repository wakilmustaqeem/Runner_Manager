# GitHub and DroidRunner adapter design

Status: design only. This document does not enable network access, credentials, package installation, runner registration, workflow dispatch, or Start/Stop controls.

## Goals

- Keep the four account slots isolated. A local label is not proof of authorization.
- Show GitHub repository/job metadata only after the user explicitly connects and grants the minimum required access.
- Keep runner-process control separate from GitHub API access: GitHub's REST API reports runner metadata but does not start or stop the local runner process.
- Preserve a safe offline dashboard when no connector is configured.

## Proposed adapter boundaries

1. `GitHubReadOnlyAdapter`
   - Fetch authenticated identity and only the repositories the identity can access.
   - Read workflow-run/job metadata and runner status only when the selected repository/organization permissions allow it.
   - Use explicit refresh, bounded pagination, timeouts, rate-limit handling, and clear stale-data timestamps.
   - Do not dispatch workflows or change repository settings in the first implementation.

2. `CredentialVault`
   - One independent authorization record per local account slot.
   - Use an approved OAuth/device authorization flow and Android Keystore-backed encryption for any persisted secret material.
   - Never accept tokens through normal text fields; never write credentials to logs, diagnostics, backups, clipboard reports, or source code.
   - Provide disconnect/revoke and account-switch confirmation. No cross-slot credential fallback.

3. `LocalRunnerAdapter`
   - Separate interface for communicating with the already-installed DroidRunner service/process.
   - Must use a documented, authenticated local IPC or explicit user-mediated handoff; do not assume Android apps can control another app's process.
   - Start/Stop remains disabled until the real DroidRunner interface is identified, permission boundaries are reviewed, and device tests prove that actions affect the intended runner only.

4. `PolicyGate`
   - Enforce mains power, minimum battery, thermal threshold, and minimum free storage at the point a job is admitted.
   - Re-check conditions before a job and while it runs where the runtime supports safe cancellation.
   - Until enforcement is implemented and tested, show policy values as “saved locally — not enforced”.

## Compatibility gate before runner control

- Confirm the actual DroidRunner version, supported CPU architecture, execution environment, and documented control interface.
- Confirm whether the official GitHub Actions runner runtime is supported in that environment. Android ARM64 alone is not proof of compatibility.
- Test a harmless sample workflow in a dedicated test repository before using valuable/private repositories.
- Never expose runner registration tokens in the UI or logs. Registration and removal require explicit confirmation and a recovery note.
- Treat workflow code as untrusted: use a separate work directory per account/repository, least privilege, no shared secrets, and cleanup only after confirming the correct path.

## First implementation sequence

1. Keep current dashboard offline by default.
2. Add adapter interfaces and test doubles; no live controls.
3. Verify authorization-flow feasibility and required GitHub permissions.
4. Implement read-only identity/repository/job refresh behind explicit consent.
5. Test account switching, disconnect, denied permissions, expired authorization, offline mode, rate limits, and audit redaction.
6. Only after compatibility evidence, implement a LocalRunnerAdapter for the actual DroidRunner interface.
7. Enable one control at a time after tests; retain a confirmation dialog and metadata-only audit entry.

## Acceptance criteria

- No network request or credential storage before explicit connection.
- Each account slot can connect/disconnect independently and never displays another slot's repositories.
- Read-only mode cannot dispatch workflows or alter repository settings.
- Start/Stop stays disabled if the DroidRunner adapter is missing, disconnected, or unverified.
- Policy settings are not represented as enforced until runtime checks are wired and tested.
- Automated tests cover permission denial, offline state, API errors, rate limits, stale data, and secret redaction.

## Billing note

A self-hosted runner may avoid GitHub-hosted runner-minute usage for eligible jobs, but it does not bypass GitHub plan limits, repository/organization policies, or other service charges. Workflow execution on a phone also has reliability, security, heat, battery, and compatibility trade-offs.
