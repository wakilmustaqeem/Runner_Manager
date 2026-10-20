# DroidRunner Integration Audit Plan

Status: planning only. No runner implementation, permissions, workflow, production, or account changes are included in this commit.

## Verified baseline

- Base branch: `main`
- Base commit audited: `8d83fd9f4f6739ed846904c3e58a3cb494104dd4`
- FM dashboard currently describes GitHub data as read-only; Start/Stop and registration are disabled.
- `GitHubDeviceFlowClient` uses tokens as method arguments and documents that it does not persist tokens or expose runner-registration/write endpoints.
- `RunnerAdapters.kt` defines interfaces only. Its policy evaluator is not yet connected to a real job-admission boundary.
- DroidRunner source references are isolated under `reference/droidrunner/`; they are not an FM implementation and must not be copied into active app source without adaptation.
- No build or GitHub Actions run is part of this audit.

## Integration phases (in order)

1. **Contract mapping (read-only):** map FM account-slot/repository selection to a single explicitly active account and selected repository. Keep `wakilmustaqeem/Runner_Manager` separate from `fmmustaqeem/fm-commerce-platform`. Do not make a repository runner start merely because an account is connected.
2. **Credential/session design:** document the credential required for runner registration, least-privilege scopes, secure storage requirements, logout/revocation behavior, and what happens after a process/device restart. Current memory-only OAuth token handling means the app cannot promise a durable login after process death. Do not store tokens in preferences, audit logs, diagnostics, or source code.
3. **Registration lifecycle design:** adapt the concepts in DroidRunner's `RunnerRegistration` only after reviewing the full credential and removal-token paths. Distinguish persistent registration from ephemeral per-job registration. Define explicit register, disconnect, re-register, and cleanup states; do not silently remove an existing registration.
4. **Runtime feasibility:** audit DroidRunner's runtime installer, command execution, process supervision, Android service/boot constraints, storage footprint, architecture compatibility, and licenses. Reference files alone are not a runnable runtime.
5. **Fail-closed job admission:** connect battery, charging, temperature, storage, network, and user-stop conditions to the actual runner admission/supervisor boundary—not only UI switches. Missing or invalid safety readings should block new jobs where required.
6. **Recovery and observability:** design bounded retry/backoff, duplicate-session protection, crash recovery, safe diagnostics, and user-visible reasons. Never print credentials or registration tokens.
7. **Verification gate:** add/adapt focused unit tests for registration, token removal/revocation, watchdog/restart, policy admission, and session conflict. Only after review request one explicit small test/build; no automatic Actions or hosted runner usage.

## Explicit non-goals

- No edits to `main`, production, FTP, repository settings, GitHub App/OAuth configuration, or Actions workflows.
- No automatic registration, runner start, workflow dispatch, or changes to the user's account selection.
- No claim that OAuth login or runner registration can remain valid indefinitely. Expiry, revocation, permissions, and GitHub policy remain authoritative.
- No APK release or production deployment.

## Exit criteria before implementation

- Confirm exact API scopes and registration/removal credential paths.
- Identify all source files and runtime assets actually required; exclude unrelated NPU/QNN features unless separately approved.
- Agree on persistent versus ephemeral behavior and disconnect semantics.
- Review a small, isolated implementation diff before any test/build.
