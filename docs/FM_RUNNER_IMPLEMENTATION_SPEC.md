# FM Runner Manager — Safe Implementation Specification

Status: **Specification only — not implemented or tested**  
Scope: Design the next implementation phase for a local-first Android self-hosted GitHub Actions runner. This document does not authorize code changes, builds, Actions runs, permission changes, runner registration, or production changes.

## 1. Non-negotiable safety boundaries

- Keep `wakilmustaqeem/Runner_Manager` separate from `fmmustaqeem/fm-commerce-platform`; never infer one account's authorization for the other.
- Only one GitHub account may be active at a time. Inactive accounts must not receive runner actions or repository requests.
- Account authorization and repository runner registration are separate explicit steps.
- Default deny: no runner process or job starts unless every admission check passes.
- No secrets in logs, audit exports, crash messages, clipboard output, or UI diagnostics.
- No GitHub-hosted Actions dependency for the target runtime; test with at most one job at a time.
- Never promise indefinite login or reconnect. Tokens can expire or be revoked; recovery must request fresh authorization when necessary.
- Disconnect, repository removal, and account switching require explicit confirmation and auditable cleanup.
- Preserve a recoverable runtime version before activation. Never delete the last known-good runtime during an update.
- Any future implementation must first be reviewed separately. This specification alone does not authorize it.

## 2. Proposed component boundaries

1. **Account session manager** — Device Flow authorization, token metadata, explicit active-account selection, revocation/expiry handling.
2. **Repository binding manager** — binds an authorized account to an explicitly selected owner/repository and verifies the current account before every write operation.
3. **Runtime verifier/installer** — downloads only pinned HTTPS artifacts, verifies a signed manifest against a trusted public key embedded in the app, checks archive digests, stages extraction, validates required files, then activates atomically with rollback.
4. **Registration controller** — obtains a short-lived repository/organization runner registration token only after explicit user action; registers the local runtime and records non-secret registration metadata.
5. **Runner service** — owns process lifecycle and exposes truthful states; UI does not claim a runner is active until process and runner evidence confirm it.
6. **Recovery scheduler/watchdog** — retries only when enabled by the user and policy permits; bounded backoff; no tight loops; records sanitized reasons.
7. **Policy/admission evaluator** — enforced at the actual process/job-admission boundary, not just by UI toggles.
8. **Audit recorder** — append-only local event records with timestamps, action, account/repository identifiers, outcome and sanitized reason; never tokens, authorization codes, private keys, or environment dumps.

## 3. State model

### Account state
- `NO_ACCOUNT`
- `AUTH_PENDING`
- `AUTHORIZED`
- `ACTIVE_ACCOUNT`
- `TOKEN_EXPIRED_OR_REVOKED`
- `DISCONNECT_PENDING`

Transitions:
- `NO_ACCOUNT -> AUTH_PENDING`: user starts Device Flow.
- `AUTH_PENDING -> AUTHORIZED`: provider confirms authorization; store secrets only through an approved encrypted secret store.
- `AUTHORIZED -> ACTIVE_ACCOUNT`: user explicitly selects this account; deactivate the previous account first.
- `ACTIVE_ACCOUNT -> TOKEN_EXPIRED_OR_REVOKED`: provider/API confirms invalid credentials; block writes and registration.
- `ACTIVE_ACCOUNT -> DISCONNECT_PENDING -> AUTHORIZED/NO_ACCOUNT`: explicit disconnect; remove local credentials and dependent registration only after confirmation and successful cleanup reporting.
- Any unexpected provider response fails closed and leaves the runner stopped.

### Repository binding state
- `UNBOUND`
- `REPOSITORIES_LOADING`
- `REPOSITORY_SELECTED`
- `BINDING_CONFIRMED`
- `BINDING_STALE`

Binding key: active account identity + exact repository owner/name + repository ID where available. Revalidate before registration and before any runner-token request. Switching accounts invalidates the active binding until the user selects and confirms a repository under the newly active identity.

### Runtime state
- `NOT_INSTALLED`
- `MANIFEST_VERIFYING`
- `DOWNLOADING`
- `STAGING`
- `VALIDATING`
- `READY`
- `UPDATE_FAILED_ROLLBACK`
- `INTEGRITY_FAILURE_BLOCKED`

The app must not enter `READY` if the trusted signing key is absent, signature verification fails, a digest mismatches, required files are missing, free space is insufficient, or the staged runtime cannot be executed in a controlled check. HTTPS alone is not a substitute for signature verification.

### Registration and runtime lifecycle
- `UNREGISTERED`
- `REGISTRATION_PENDING`
- `REGISTERED_STOPPED`
- `START_PENDING`
- `RUNNING_CONFIRMED`
- `STOP_PENDING`
- `RECOVERY_WAIT`
- `REGISTRATION_FAILED`
- `RUNTIME_FAILED`
- `DISCONNECT_PENDING`

Rules:
- Registration is allowed only from `BINDING_CONFIRMED` + valid active account + `READY` runtime + passing admission checks.
- A temporary registration token is used only for the registration operation; do not persist it longer than required or log it.
- `RUNNING_CONFIRMED` requires process health plus runner/session evidence; a successful button tap is not evidence.
- Start is blocked if the account/repository binding is stale, runtime integrity is not verified, permissions are missing, the device policy fails, or another job is active.
- Stop is idempotent and must report whether the process actually stopped.
- If the chosen registration mode is ephemeral, record that it deregisters after a job and require explicit/recovery-policy re-registration before another job.
- Recovery uses bounded exponential backoff and stops after a configured retry limit or any integrity/authentication failure. Authentication failures require user action.
- Disconnect stops the process first, then attempts GitHub-side deregistration when valid credentials permit, then clears local registration/binding/credential data. Report partial cleanup honestly; do not claim remote deregistration if it was not confirmed.

## 4. Job admission policy

Immediately before a runner process starts or accepts a job, enforce:
- exactly one active account and one explicitly bound repository;
- valid credential/session state for the requested operation;
- verified runtime and approved runtime version;
- no concurrent job;
- user-enabled runner operation and recovery policy;
- battery, charging, thermal and free-storage thresholds configured by the user;
- Android service/notification/background restrictions and required permissions are satisfied;
- no pending disconnect, account switch, runtime update, or integrity alarm.

Any failed or unknown check means **DENY** with a clear, sanitized reason. UI-only flags do not count as enforcement.

## 5. Credential and privacy requirements

- Use Android Keystore-backed encryption for persisted secrets; review key invalidation and backup behavior.
- Store the minimum credential material needed. Prefer supported renewal flows where available; never assume a token is permanent.
- Keep authorization codes, access tokens, runner registration tokens, private keys, and process environments out of audit events.
- On account switch, ensure old-account credentials cannot be used by the new active session.
- On disconnect, clear local secrets and caches related to the selected identity after the cleanup workflow; avoid deleting unrelated account data.
- Do not copy DroidRunner credential fallback behavior without a separate security review.
- Network requests must use TLS with host validation. Reject unexpected redirects for sensitive operations unless explicitly validated.

## 6. Runtime installation and recovery requirements

- Pin the runtime release/version and artifact source.
- Require a trusted manifest signature and SHA-256 digest validation; missing trust configuration is a hard failure.
- Download to a temporary path; validate available storage before download and extraction.
- Extract into a staging directory, reject path traversal, validate required binaries and metadata, then atomically activate.
- Keep the previous known-good runtime until the new version passes post-install checks.
- Recover from interruption without marking partial data as installed.
- Do not silently downgrade or switch release sources.
- Keep runtime license/provenance inventory for every redistributed component, including PRoot and bundled runtime assets, before any public release.

## 7. Android lifecycle and user controls

- Show distinct actions for **Connect account**, **Select repository**, **Register runner**, **Start**, **Stop**, and **Disconnect**.
- Show the exact active account and repository in the control panel before any mutating action.
- Require confirmation for registration, account switching while registered, deregistration, and disconnect.
- Show actual process/service state, last heartbeat, last recovery attempt, and sanitized last failure.
- Boot recovery is opt-in and must run only if runtime and registration are configured and policy passes.
- Explain that Android vendor battery management, OS process limits, network loss, and GitHub credential expiry can interrupt service; never guarantee always-on operation.
- Provide an emergency stop that prevents restart until the user explicitly re-enables it.

## 8. Minimum verification matrix before implementation is considered ready

| Area | Required tests |
|---|---|
| Account isolation | Two identities; switch active account; prove requests cannot use inactive identity |
| Device Flow | User cancels, code expires, network fails, authorization denied, token revoked |
| Repository binding | Private repository access denied; repository renamed/deleted; stale owner/name; account switched |
| Runtime trust | Missing signing key, bad signature, digest mismatch, corrupt archive, HTTPS failure |
| Runtime recovery | Interrupted download/extraction, insufficient storage, failed activation, rollback to last known-good version |
| Registration | Token rejected/expired, insufficient permissions, repository/org mismatch, cancellation, remote cleanup failure |
| Process lifecycle | Start failure, process crash, stop timeout, duplicate start, app restart, device reboot |
| Job policy | Low battery, high temperature, low storage, concurrent job, disabled operation, pending disconnect |
| Ephemeral mode | Job completes and deregisters; next job remains blocked until permitted re-registration |
| Privacy | Assert tokens, codes, private keys and environment variables never appear in logs or exports |
| Licensing | Per-component license and source-obligation review before redistribution |
| Device compatibility | Controlled ARM64/API 33 test on target hardware; do not infer compatibility solely from architecture labels |

## 9. Delivery gates

1. Review this specification and confirm scope.
2. Approve a separate code-change proposal naming exact files and tests.
3. Implement one small component at a time on a non-main branch.
4. Review diff and threat model before any build.
5. Run only an explicitly approved, single diagnostic/build/job.
6. Verify behavior on the target Android device and record evidence.
7. Conduct license/provenance and privacy review before any public distribution.

## 10. Current truth

The existing FM Runner Manager main branch is a read-only GitHub metadata dashboard with runner controls disabled. This document is a design specification, not evidence of working registration, runtime installation, start/stop, or automatic reconnect.
