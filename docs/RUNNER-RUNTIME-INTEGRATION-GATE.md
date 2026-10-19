# Runner runtime integration gate

Status: design gate only. This document does not add a foreground service, install a runtime, or control DroidRunner. FM Runner Manager must remain honest about that limitation.

## Verified boundary today

- The app's Android manifest declares INTERNET and MainActivity only; no runner foreground service is declared.
- The current local runner adapter is an interface/policy boundary, not an implementation that starts or supervises a process.
- The registration-token client requests a short-lived GitHub token only. A token is not a registered runner and must not be logged, displayed, persisted, copied, or requested before the runtime is ready.
- Upstream DroidRunner's RunnerService is not exported. No documented public IPC contract has been verified, so FM Runner Manager must not attempt to bind to it by guessed component names or reflection.
- Runtime installation is fail-closed in the policy helpers, but the signature/digest/layout checks are not yet wired to an installer. With no explicitly trusted pinned signing key, installation must remain blocked.

## Required architecture before real controls

1. **Runtime trust** — Decide which runtime distribution is trusted and who owns its signing key. Pin only a verified public key; never commit a private key. Verify exact manifest bytes/signature, strict fields, streamed archive digest, archive entry paths, extracted layout, and executable permissions before atomic activation. No fallback to unsigned or unverifiable content.
2. **License review** — Upstream DroidRunner is GPL-2.0-only. Before copying/adapting its code or redistributing a combined APK, review and satisfy license, source-disclosure, notices, and third-party runtime/rootfs obligations. Do not silently treat upstream code as a permissively licensed library.
3. **Foreground service** — A dedicated non-exported Android foreground service must own the child process lifecycle, notification, wake-lock policy, and bounded restart behavior. Add only the Android permissions/types actually required for the target SDK and test Android 13+ behavior. Never start the runner from UI state alone.
4. **Process ownership** — Persist a random per-install instance ID and exact managed working-directory identity. Track process start, PID/process handle where reliable, exit code, timestamps, and stop result. A PID alone is not proof of process identity. Do not kill arbitrary processes or recursively delete an unverified path.
5. **Registration lifecycle** — Confirm authenticated GitHub identity, exact owner/repository, and effective registration permission before requesting a short-lived token. Deliver it directly to the verified runtime in memory. On failure, redact secrets and show a safe recovery state. Support reconnect/re-registration without pretending a cached token never expires.
6. **Truthful status** — Derive status from fresh service/process observations and GitHub runner state. Online requires all three; metadata alone is insufficient. Distinguish Not installed, Blocked by trust, Disconnected, Starting, Running but not online, Online, Stopping, and Failed.
7. **Policy enforcement** — Battery, thermal, storage, network, and one-job-at-a-time policies must be enforced by the service/job admission layer, not merely saved or shown in UI. Fail closed when measurements are missing or stale.
8. **Testing** — First run local JVM tests and an Android build without starting Actions jobs. Then install on a dedicated test device/repository, register only after explicit confirmation, run at most one harmless job, verify logs are redacted, test stop/restart/network loss, and confirm GitHub online/offline state. Do not test against FM Commerce until the dedicated test passes.

## Explicit stop conditions

Implementation must stop before download, installation, token issuance, registration, or process start if any of the following is true:
- no trusted pinned signing key or unverifiable manifest;
- archive digest mismatch, unsafe archive path, or runtime layout failure;
- unclear license/distribution obligations;
- unverified account/repository/registration permission;
- no verified service/process adapter;
- policy state is missing or stale.

## Current truthful status

GitHub connection and repository metadata are separate from runner runtime readiness. The app must continue to show runner registration/control as unavailable until the gates above are implemented and verified on-device. This document does not claim that a runner is active.