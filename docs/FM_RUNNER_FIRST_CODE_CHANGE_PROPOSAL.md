# FM Runner Manager — First Code-Change Proposal

Status: **Proposal only — not approved for implementation**  
Branch for this proposal: `feat/droidrunner-integration-audit`  
Target repository: `wakilmustaqeem/Runner_Manager`

## 1. Proposed first increment

Implement a **pure Kotlin runner lifecycle state model and transition validator only**. This is the smallest useful foundation for the larger implementation: it makes invalid lifecycle transitions explicit without starting a process, contacting GitHub, registering a runner, storing credentials, changing UI controls, or enabling background recovery.

The first increment must not implement a live runner or claim that the app is now an active runner.

## 2. Exact proposed file scope

### Add
- `app/src/main/java/com/fm/runnermanager/runner/RunnerLifecycleState.kt`
  - Define typed states for unregistered, registration pending, registered/stopped, start pending, running-confirmed, stop pending, recovery wait, registration/runtime failure, and disconnect pending.
  - Define typed events and a pure transition function.
  - Reject unknown or unsafe transitions with a typed denial result.
  - Keep account/repository identity as explicit non-secret context; never accept tokens or secrets in the state object.
  - Make `RUNNING_CONFIRMED` require a separate evidence input representing verified process/session health; a UI click alone must never produce this state.
  - Include explicit terminal/blocking handling for authentication invalidation and runtime integrity failure.
  - Keep recovery bounded by policy inputs; no timers, service, networking, persistence, or Android APIs in this increment.

### Add tests
- `app/src/test/java/com/fm/runnermanager/runner/RunnerLifecycleStateTest.kt`
  - Unit tests for every allowed transition.
  - Tests that start cannot skip registration or runtime readiness.
  - Tests that a button/request event alone cannot claim running-confirmed.
  - Tests that auth invalidation, integrity failure, and disconnect block start/recovery.
  - Tests for repeated stop and duplicate start behavior.
  - Tests for account/repository mismatch and ephemeral-runner post-job state if those inputs are included in the first model.

### Build configuration
- Inspect existing Gradle test dependencies before editing. The currently audited `app/build.gradle.kts` configures Android/Kotlin plugins and Java/Kotlin 17 but does not show test dependencies.
- If the project has no usable local unit-test setup, propose the minimum test-only dependency/config change in a separate reviewed diff; do not silently add dependencies as part of the first code patch.

## 3. Explicitly out of scope

- No edits to `MainActivity.kt`, `GitHubDeviceFlowClient.kt`, `RunnerAdapters.kt`, manifest, permissions, secrets, or app settings.
- No GitHub write endpoints, runner-token endpoints, repository registration/deregistration, process execution, PRoot/runtime download, watchdog, boot receiver, account persistence, or UI wiring.
- No changes to `main`, no PR opening/merging, no build, no GitHub Actions run, no runner registration, no production/FTP changes.
- No copying or repackaging upstream DroidRunner binaries or runtime assets.

## 4. Acceptance criteria

1. The state model is pure Kotlin and deterministic.
2. Every transition is explicit; unrecognized transitions fail closed.
3. Account and repository binding are checked before a state can move toward registration/start.
4. No transition can declare a runner active without explicit health evidence.
5. Authentication invalidation and runtime-integrity failures prevent start and automatic recovery.
6. Tests cover positive and negative transitions, including duplicate actions and disconnect.
7. Existing UI remains read-only and runner controls remain disabled.
8. Diff is reviewed before any build; any build/test execution requires a separate explicit approval.
9. The implementation is developed on a non-main branch and does not change the audited `main` commit.

## 5. Risks and review questions

- Define exactly what constitutes trustworthy process/session health before any later adapter can supply that evidence.
- Decide whether account/repository mismatch is represented in this state model or a separate binding guard; do not duplicate sources of truth.
- Confirm the project’s existing test runner and dependency policy before adding any test dependencies.
- Keep ephemeral and persistent registration modes explicit; do not assume either mode without a later product decision.
- Keep this first increment disconnected from production actions so tests cannot register or start a real runner.

## 6. Approval gate

This document requests review of the scope only. **No implementation is authorized by creating this proposal.** The next step is for the owner to approve or revise the exact file scope. After approval, prepare a patch on a non-main branch, inspect the diff, and ask separately before running tests or builds.
