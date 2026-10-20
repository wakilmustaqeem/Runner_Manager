# DroidRunner Registration Compatibility Audit

Audit type: source inspection only. This is not an implementation and does not authorize a runner to start.

## Source observations

Inspected DroidRunner `main` at the file revisions below:

- `RunnerRegistration.kt` — `6dd6e6a48e6b4685bd0fd035afe37daca455bc69`
- `GitHubApi.kt` — `8cd8d2d519a6bb21282d63526504303500ac3d58`
- `SecretStore.kt` — `62512ae2b193a7db8e3349d844c95cb55b1e4227`
- `RunnerService.kt` — `54b80b8db4343c06e4ee88d92a23841fe4ef69e5`
- `ServiceLifetime.kt` — `682d5c395dee391188567aba390aa6ec2907af1d`
- `AndroidManifest.xml` — `72c94566446a7e29943e7e1ba115da711897d887`

Upstream source links:
- https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerRegistration.kt
- https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/github/GitHubApi.kt
- https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/security/SecretStore.kt
- https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerService.kt
- https://github.com/m96-chan/DroidRunner/blob/main/app/src/main/AndroidManifest.xml

## Findings

### 1. Registration is not just a UI/API call

DroidRunner obtains a short-lived runner registration token from the repository or organization Actions runner-token endpoint, then configures the installed runner runtime. The runtime and process supervisor are required; calling the endpoint alone cannot make this FM app a working self-hosted runner.

The source also has a separate removal-token path. Disconnect/cleanup therefore needs an explicit, reviewed lifecycle; deleting local UI state alone is not equivalent to removing a registered runner.

### 2. Persistent and ephemeral registration are distinct

DroidRunner's registration metadata records whether the runtime is registered and which credential source was selected. The source comments state that an ephemeral runner deregisters itself after a job and needs to register again for subsequent jobs. Switching ephemeral mode also requires re-registration.

FM must not infer that an existing registration is reusable or automatically remove it. The user-facing choice and current registration state must agree, with explicit confirmation for disconnect or destructive cleanup.

### 3. Credential fallback must be intentional

DroidRunner distinguishes a supplied credential, signed-in token, and stored PAT. It avoids silently replacing an explicitly selected credential with another. Its removal path may try a distinct sign-in credential after authorization refusal, but only for selected 401/403/404 responses.

FM should not copy that fallback blindly. The intended account, repository visibility, credential source, scopes and error message must be visible and tested. Credentials must never appear in logs, audit exports, exception text or UI diagnostics.

### 4. Durable login requires a secure storage design

DroidRunner's `SecretStore` encrypts stored values with AES-GCM and an Android Keystore key; it stores access-token expiry and refresh-token data where available and uses synchronous commits for security-critical removals.

FM's current `GitHubDeviceFlowClient` accepts access tokens as method arguments and explicitly does not persist them. A future persistent session needs a separate reviewed encrypted store and disconnect/revocation semantics. Do not claim a login never expires: GitHub may expire or revoke credentials and permissions.

### 5. Android service and permissions are part of the runtime

DroidRunner declares internet, foreground-service, notification, wake-lock, battery-optimization-exemption request and boot-receiver permissions, and includes a foreground runner service plus a watchdog job service. These are reference observations, not a recommendation to copy every permission into FM. Each permission must be justified against FM's actual target SDK, Android restrictions, user consent and required runtime behavior.

### 6. FM integration gap confirmed

In FM `main`:
- `GitHubDeviceFlowClient.kt` is a Device Flow and read-only REST client foundation. It intentionally does not implement runner registration or write/dispatch operations.
- `RunnerAdapters.kt` contains interfaces; comments explicitly say no production adapter is provided and runner controls remain disabled until implementations are authorized and verified.
- The policy evaluator's own comment says it is authoritative only if invoked at the actual job-admission boundary.

Therefore no production registration/start/stop implementation should be claimed yet.

## Next gated steps

1. Inspect the exact runner runtime/CLI licensing, distribution and architecture support before proposing any runtime reuse.
2. Confirm current GitHub documentation for fine-grained token permissions and repository/organization runner management before selecting scopes; do not guess permissions.
3. Specify the account/repository binding, persistent-versus-ephemeral mode, restart recovery and disconnect cleanup as state transitions.
4. Design a narrow adapter contract and tests without enabling UI controls.
5. Review the proposed diff. Only then request separate approval for one diagnostic test/build.

## Safety boundary

No changes to `main`, workflows, GitHub settings, credentials, production, FTP, account selection or runner state were made by this audit. No build or Actions run was started.
