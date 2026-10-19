# FM Runner Manager — real runner runtime integration

Status: source audit complete; runtime port/integration has not started. No runtime token has been requested, no runner registered, and no build or Actions job run.

## Decision

Do not build a runner around the GitHub REST API alone. The API can issue a short-lived registration token, but only a running official Actions Runner process can connect to GitHub, accept jobs, and execute workflow steps.

Use the public [DroidRunner](https://github.com/m96-chan/DroidRunner) implementation as the technical base/reference for the runtime layer. It already implements an official Linux ARM64 Actions Runner under PRoot, signed runtime-bundle verification, registration, a foreground service, listener supervision, device policy gates, and runner-state reporting. Do not assume FM Runner Manager can control another APK: no supported cross-app IPC contract has been verified.

## Upstream source map

- [RunnerRegistration.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerRegistration.kt): repository target registration and credential handling.
- [RunnerService.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerService.kt): foreground service and runner supervision.
- [RuntimeInstaller.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runtime/RuntimeInstaller.kt): runtime bundle retrieval and integrity checks.
- [GitHubApi.kt](https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/github/GitHubApi.kt): GitHub App authorization and installation/repository discovery.
- [ARCHITECTURE.md](https://github.com/m96-chan/DroidRunner/blob/main/docs/ARCHITECTURE.md): trust boundaries and runtime design.
- [README.md](https://github.com/m96-chan/DroidRunner/blob/main/README.md): setup, permission requirements, supported device/runtime constraints.

## Authentication correction

The current FM Runner Manager branch requests OAuth scopes `repo read:user`. This is suitable only for a classic OAuth App model and requests broad repository access; it does not match the GitHub App installation model described by DroidRunner.

Before the runtime is wired, configure a dedicated GitHub App with Device Flow enabled and repository Administration read/write permission for the target repository, install it only on the intended repository, and configure its public Client ID and slug in the build. Do not reuse a Client ID of unknown type. Confirm the effective installation and repository before requesting a runner token.

If the existing FM Runner Manager OAuth App must be retained instead, pause the GitHub App migration and explicitly review the broad classic OAuth `repo` scope first.

## Integration path

1. Preserve the existing FM dashboard and account separation, but replace the incorrect OAuth-scope assumption with an explicit GitHub App authentication/installation model.
2. Port or adapt the upstream GitHub App API, token/session handling, and repository target picker with attribution and licence notices.
3. Port the runtime installer only with signed manifest verification, archive digest verification, and a pinned/verified runtime source. Never download and execute an unverified bundle.
4. Port the foreground runner service, PRoot invocation, job policy enforcement, listener supervision, and state stream. Do not create a fake status or mark the runner Active merely because registration-token issuance succeeded.
5. Keep the registration token in memory only and pass it directly to the runtime's configuration process; never render it in a dialog, audit record, clipboard, or exception.
6. Add a separate Register Runner confirmation showing the authenticated account, exact repository, and required Administration permission. Do not register on connect.
7. Test one harmless workflow on a dedicated test repository. Only after the runner is shown online and the job completes on this phone should FM Commerce be enabled.

## GPL-2.0 compliance gate

DroidRunner states that it is licensed GPL-2.0-only. If code is copied or adapted into FM Runner Manager and the resulting app is distributed, comply with the licence, preserve copyright and notices, provide corresponding source and modifications, and ensure distribution terms are compatible. Keep upstream attribution and third-party runtime/rootfs notices. Review the exact licence before publishing or selling a modified APK.

## Safety and acceptance criteria

- Keep the work on `fix/private-repository-permissions-v1-2026-10-19`; no `main` or production changes without separate approval.
- No runner token request or registration until the actual runtime consumes it.
- No APK build or Actions job until explicitly requested.
- No active/online claim until GitHub and the local listener both confirm the registered runner state.
- One job at a time, and first test only a harmless workflow on a dedicated repository.
- Keep FM Commerce and `wakilmustaqeem/Runner_Manager` account identities distinct.
