# Activation Wiring Design — FM Runner Manager

**Repository:** `wakilmustaqeem/Runner_Manager`  
**Proposed path:** `docs/ACTIVATION-WIRING-DESIGN.md`  
**Status:** DESIGN DRAFT — SOURCE AUDIT INCOMPLETE  
**Implementation status:** NOT STARTED  
**Audit status:** UNVERIFIED where explicitly marked below

## 1. Purpose and scope

This document defines a safe activation flow between GitHub repository selection, runner registration, runtime configuration, and the foreground service.

It is a design document, not an implementation or test report.

### Excluded from this change

- Kotlin or other source-file changes
- Build, installation, or runtime execution
- GitHub Actions or workflow dispatch
- Changes to `main`, production, FTP, or FM Commerce
- Logging or documenting access tokens, registration tokens, or other secrets

Implementation work requires separate review and explicit approval.

## 2. Approved design decisions

### B1 — CommandProvider: Option B

The provider's intended responsibility is limited to:

- Supplying `Paths` for the selected and validated runtime.
- Reporting runtime readiness through `runtimeVerified()`.

Registration and listener commands will not be hidden behind an ambiguous provider method. Command construction will remain in the appropriate orchestration/service layer.

**Interface compatibility remains UNVERIFIED.** The existing `CommandProvider.commandFactory(): ProcessBuilder` may be incompatible with Option B. Before implementation, decide whether to:
- change/deprecate the current interface so it supplies only `Paths` and `runtimeVerified()`, or
- introduce a dedicated `RuntimePathsProvider` interface.

No interface choice is finalized until the caller audit and current implementation are verified.

### B2 — Repository binding: Option B+C hybrid

Both mechanisms will be used:

1. The session store retains verifiable account and selected-repository context.
2. `GrantHolder`, or an equivalent coordinator-side binding, manages the narrowly scoped lifecycle of a registration grant.

Before each registration or activation action, validate the account, repository owner/name, and associated session binding.

A grant from another account or repository must never be reused. Matching only the repository name is not sufficient authorization.

### B3 — Cached login: Option A with TTL

Cached identity may support temporary UI and session context.

- The cache TTL must be explicit and configurable.
- Expired or invalid cache must not be treated as active authority.
- Sensitive actions require fresh identity verification or a documented revalidation policy.
- Network failure is not successful identity verification.
- Account switching or disconnect must clear or invalidate the related session context.

**The numeric TTL is deferred to implementation planning.**

### B4 — RegistrationCoordinator: Option C

A separate asynchronous `RegistrationCoordinator` will manage registration:

1. Verify explicit user confirmation.
2. Validate session, account, and repository binding.
3. Verify runtime readiness.
4. Request a repository-scoped registration grant.
5. Prepare and execute the registration configuration command.
6. Check the process result and registration outcome.
7. Authorize listener startup only after successful verification.
8. Report status safely and perform appropriate cleanup.

A successful configuration-process exit alone does not prove that the runner was correctly registered with GitHub. A reliable postcondition must be defined and checked.

Operations must not block the UI thread. Duplicate or overlapping activation attempts must be prevented.

## 3. Caller-by-caller audit — seven checks

**Current result: UNVERIFIED.** Actual terminal outputs for the checks below are not available in this document's evidence. No check is being described as PASS, EMPTY, or fully audited.

| # | Required check | Status |
|---|---|---|
| 1 | Callers of `configure()` | UNVERIFIED |
| 2 | Callers of `ProotRunnerCommand.run()` / `run(paths)` | UNVERIFIED |
| 3 | Process start or `waitFor()` usage after `configure()` | UNVERIFIED |
| 4 | Configuration process exit-code/result checks | UNVERIFIED |
| 5 | Construction and data flow of `ProotRunnerCommand.Paths` | UNVERIFIED |
| 6 | `runtimeVerified()` implementations and call sites | UNVERIFIED |
| 7 | `CommandProvider` implementations and registration sites | UNVERIFIED |

These statuses indicate missing audit evidence; they do not prove that relevant code is absent.

All seven checks must be verified against the relevant repository branch before implementation. Until then, activation wiring is not proven complete.

## 4. Current state mismatch

Earlier inspection reported the presence of GitHub Device Flow, identity lookup, and repository-listing code. Separately available device status showed:

- GitHub connection: Not connected
- Repositories loaded: 0

These observations are not established to be from the same app session or time. The root cause is therefore unknown.

Possible explanations include an expired or unrestored session, account-context mismatch, repository-request failure, pagination/permission issues, or a UI state-refresh defect. These are hypotheses, not verified findings.

### Required behavior

- The UI must display state verified for the current session.
- Cached metadata must not be represented as proof of fresh authentication.
- A failed refresh must not silently present stale data as current.
- Authentication failure and repository-loading failure must have distinct statuses.
- Registration and Start remain blocked until account and selected repository are validated.

## 5. Proposed activation flow

1. The user connects a GitHub account.
2. The app verifies identity and loads repositories.
3. The user selects a repository.
4. The app validates the account/repository binding.
5. Runtime paths and runtime integrity are verified.
6. The user explicitly approves registration.
7. `RegistrationCoordinator` obtains a short-lived registration grant.
8. The configuration process runs asynchronously and its exit result is checked.
9. The required runner-registration postcondition is verified.
10. Only after verification, the listener-start handoff is authorized.
11. The service then:
    a. validates the explicit start intent, nonce, and `GrantHolder` handoff;  
    b. obtains `Paths` and checks `runtimeVerified()` from the approved provider;  
    c. constructs the listener command itself using `ProotRunnerCommand.run(paths)`; and  
    d. passes the resulting command to `RunnerProcessSupervisor.start()` (subject to verification of the actual API and signatures).
12. Stop, disconnect, or failure safely stops the relevant process and invalidates related session/grant state.

**Step 11's exact APIs and handoff mechanism remain UNVERIFIED** until the seven source checks and interface decision are complete. The sequence above is the intended design, not a claim that this wiring already exists.

If any required validation fails, the flow must not proceed.

## 6. RegistrationCoordinator state machine

The intended high-level states are:

`idle → confirming → granted → configuring → verifying → ready`

Any unrecoverable failure transitions to `failed`. A new attempt must explicitly reset or recreate the failed operation; it must not silently reuse an old grant.

State transitions must be guarded against duplicate requests, cancellation, timeout, account/repository changes, and stale asynchronous callbacks. Grant invalidation and cleanup are required on failure, disconnect, or context mismatch.

The precise transition guards, cancellation semantics, and whether intermediate states such as `validating` or `registering` are needed remain implementation decisions.

## 7. Failure matrix

| Failure condition | Required behavior | Safe outcome |
|---|---|---|
| GitHub identity cannot be verified | Block registration/Start | Fail closed |
| Session TTL expires | Require revalidation | Fail closed |
| Account and selected repository mismatch | Reject grant | Fail closed |
| Repository permission insufficient | Show clear error | No activation |
| Runtime paths incomplete | Do not run configuration | Fail closed |
| Runtime integrity verification fails | Block Start | Fail closed |
| Registration grant unavailable | Report failure without exposing secrets | No listener start |
| Configuration process fails or times out | Handle result safely; do not assume registration succeeded | No listener start |
| Registration postcondition cannot be verified | Block activation | Fail closed |
| Listener command/provider unavailable | Reject service start | Fail closed |
| Process crashes | Apply a bounded, explicit recovery policy | No unauthorized restart |
| Disconnect occurs | Invalidate relevant session and grants | No reuse of old context |
| Duplicate activation request | Permit only one active attempt | Protect against races |
| Network verification fails | Do not treat as authentication success | Sensitive action blocked |

## 8. Security and lifecycle requirements

- A registration token is used only for the intended registration operation.
- Tokens, device codes, and other credentials must not appear in logs, audit reports, or UI error messages.
- Credentials require appropriate secure storage and a limited lifecycle.
- Repository owner/name must be validated in the authenticated account context.
- User confirmation, runtime verification, and process result are separate conditions; none substitutes for another.
- The service must not start with an unverified or incomplete command.
- Registration success, listener-process startup, and the runner's online status on GitHub are distinct states.
- Disconnect and account switch must not transfer prior authorization into a new context.

## 9. Documentation acceptance criteria

Before this design is considered ready for implementation:

- [ ] Obtain actual outputs or clear source references for all seven caller-audit checks.
- [ ] Verify each UNVERIFIED item or retain it explicitly as an unresolved blocker.
- [ ] Define a reproducible diagnostic plan for the account/session state mismatch.
- [ ] Define the TTL duration and revalidation policy.
- [ ] Define a reliable registration-success postcondition.
- [ ] Clarify the B1 `CommandProvider` interface fate: retain, change/deprecate, or replace with `RuntimePathsProvider`.
- [ ] Specify the `RegistrationCoordinator` state machine and transition guards (`idle → confirming → granted → configuring → verifying → ready`, with failure/cancellation handling).
- [ ] Clarify interfaces between `RegistrationCoordinator` and the foreground service.
- [ ] Approve a plan to convert the failure matrix into implementation and test cases.
- [ ] Confirm a safe feature branch and separate implementation scope before any code change.

## 10. Reported progress estimates

| Area | Estimate | Interpretation |
|---|---:|---|
| Build system | 20% | Previously reported estimate; not reverified in this document |
| Activation wiring — design | 25% | Design decisions documented; caller audit remains incomplete |
| Activation wiring — implementation | 5% | Previously reported estimate; implementation not verified as complete |
| Overall | Approximately 23% | Previously reported estimate; unchanged |

These are estimates, not measures of automated test coverage or verified completion.

## 11. Final status

**Design decisions:** B1, B2, B3, and B4 accepted.

**Audit:** All seven checks remain UNVERIFIED.

**Implementation:** Not started.

**Build/install/runtime tests:** Not performed as part of this documentation change.

**Scope:** Documentation only; no source-code changes, build, tests, or CI.

The next step after this documentation commit is to run the seven source checks when practical, resolve the B1 interface choice, and review the implementation scope before starting implementation.
