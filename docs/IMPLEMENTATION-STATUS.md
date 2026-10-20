# FM Runner Manager Implementation Status

Branch: `feat/independent-runner-architecture-v1`

This is an implementation status record, not a claim that the app is production-ready. No APK build or on-device validation has been performed as part of this change set.

## Implemented in source

- Independent architecture plan: FM Runner Manager has no planned runtime dependency on, or control interface to, DroidRunner.
- GitHub App Device Flow and read-only REST client foundation.
- Android Keystore-backed AES-GCM credential vault at `app/src/main/java/com/fm/runnermanager/security/CredentialVault.kt`.
- Encrypted token persistence by account slot; the Activity clears its in-memory token map on account switching and loads/verifies only the selected account's saved token.
- Account removal clears the selected slot's encrypted token and stored local identity/client-ID metadata.
- Existing runner Start/Stop controls remain disabled.
- Policy evaluator exists, but it is not wired into a real job-admission boundary.

## Not yet implemented or verified

- No independent FM runner runtime or process supervisor.
- No production foreground runner service, watchdog/recovery, job workspace isolation, or actual job-admission hook.
- No proven compatibility of the official GitHub Actions runner runtime with the target Android 13 ARM64 device.
- No on-device verification of Android Keystore behavior, token restoration, account switching, expiry/revocation, or app-process recreation.
- No compile/test/APK build verification performed for this change set.
- No live runner registration or workflow execution.

## Required next gates

1. Run static review and compile/unit checks in a controlled environment after the user authorizes a build.
2. On-device test vault save/load/clear, process recreation, account switch, and network failure without exposing token values.
3. Verify saved client ID and GitHub identity restoration; require reauthorization for revoked/expired sessions.
4. Independently establish runtime compatibility and security boundaries before implementing a foreground service or runner registration.
5. Only after explicit approval, use a dedicated private test repository and one harmless job at a time.

## Safety invariants

- No runner registration, workflow dispatch, or production repository job has been enabled by this work.
- Do not represent saved policy values as enforced until checks are connected to the actual runtime job-admission boundary.
- Android Keystore protects stored token material at rest, but it does not make a token permanent or prevent GitHub from revoking it.
