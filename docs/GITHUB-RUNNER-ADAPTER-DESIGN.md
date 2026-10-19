# GitHub and DroidRunner adapter design

Status: implementation in progress on `fix/private-repository-permissions-v1-2026-10-19`. The registration-token adapter now exists as an isolated code component, but is not wired to UI or a local runner runtime. No token has been requested and no runner has been registered.

## Goals

- Keep account slots isolated. A local label is not proof of authorization.
- Show repositories only after explicit connection and permission grant.
- Keep GitHub registration-token issuance separate from local runner installation/registration and process control.
- Preserve a safe offline dashboard when no connector is configured.

## Authentication and permissions gate

The current app obtains `GITHUB_APP_CLIENT_ID` from the build property `githubAppClientId` or environment variable `GITHUB_APP_CLIENT_ID`. The committed repository does not identify which GitHub Developer Settings application owns the configured value; a local build property/environment value is not present in source control.

Before enabling private-repository access or registration, confirm whether the configured Client ID belongs to:

- **Classic OAuth App:** the current Device Flow requests the broad `repo` scope plus `read:user`. The `repo` scope can permit repository write operations; it is not read-only.
- **GitHub App:** permissions are configured on the GitHub App and its installation/user authorization. Do not assume the OAuth `repo` scope grants GitHub App installation permissions. Request only the permissions actually needed and install/authorize the app for the target repository.

For repository runner registration, the registration-token API requires repository administration authority. For fine-grained credentials and GitHub App tokens, the relevant permission is typically **Administration: write** on the selected repository. A classic OAuth token may use its broad `repo` scope. Do not issue a token until the selected repository, authenticated identity, and effective permission are verified and the user explicitly confirms registration.

## Proposed adapter boundaries

1. `GitHubMetadataAdapter`
   - Fetch authenticated identity and only repositories visible to that identity.
   - Fetch workflow-run and runner metadata only when permissions allow.
   - Use explicit refresh, bounded pagination, timeouts, rate-limit handling, and stale-data timestamps.

2. `GitHubRunnerRegistrationClient` (initial code added; not yet wired)
   - Request a short-lived repository registration token from GitHub.
   - Validate owner/repository path components and avoid including response bodies in errors.
   - Keep the returned token in memory only and pass it directly to a verified runtime.
   - Never display, persist, copy, or log the token.
   - This API call only obtains a token; it does not register or start a runner.

3. `CredentialVault`
   - One independent authorization record per account slot.
   - Review encrypted storage with Android Keystore before persisting any credential.
   - Never accept tokens in ordinary text fields or include secrets in logs, diagnostics, backups, or clipboard reports.
   - Provide disconnect/revoke and account-switch confirmation. No cross-slot credential fallback.

4. `LocalRunnerAdapter`
   - Separate interface for the already-installed DroidRunner process/runtime.
   - Must use a documented, authenticated local IPC or explicit user-mediated handoff. Do not assume Android apps can control another app's process.
   - Start/Stop stays disabled until the real interface is identified and device tests prove that actions affect the intended runner only.

5. `PolicyGate`
   - Enforce power, battery, thermal, and free-storage requirements at job admission.
   - Re-check conditions before a job and during execution where supported.
   - Until enforcement is wired and tested, show policies as “saved locally — not enforced”.

## Runtime compatibility gate

- Confirm DroidRunner version, CPU architecture, execution environment, and documented control/handoff interface.
- Confirm the official GitHub Actions runner runtime works in that environment. Android ARM64 alone is not proof of compatibility.
- Test one harmless sample workflow in a dedicated test repository before using FM Commerce.
- Treat workflow code as untrusted: isolate work directories per account/repository, limit secrets and privileges, and never delete a work directory without verifying its exact path.

## Implementation sequence

1. Verify configured Client ID type and required permissions.
2. Verify identity, selected repository visibility, and effective registration permission.
3. Add explicit Register Runner confirmation with account/repository identity and audit metadata.
4. Only after confirmation, request a short-lived registration token and hand it directly to a verified local runtime; never render the token.
5. Implement the actual DroidRunner/runtime bridge and prove registration, reconnect, and stop behavior on-device.
6. Run one harmless test job; verify GitHub reports the expected runner and job result before enabling FM Commerce jobs.
7. Add disconnect/revoke and cleanup paths with account isolation and recovery guidance.

## Acceptance criteria

- No network request or token issuance before explicit user action.
- Each account slot is isolated and never displays another slot's repositories or credentials.
- Permission denial, offline state, API errors, rate limits, stale metadata, and secret redaction are handled.
- Registration-token issuance is not represented as a registered runner.
- Start/Stop remains disabled if the runtime adapter is missing, disconnected, or unverified.
- No UI says Active/Online unless a live runtime/GitHub check confirms it.
- Resource policy is not represented as enforced until runtime checks are wired and tested.

## Billing note

A self-hosted runner may avoid GitHub-hosted runner-minute usage for eligible jobs, but it does not bypass GitHub plan limits, repository/organization policy, or other service charges. Phone-based execution also has reliability, heat, battery, storage, and security trade-offs.
