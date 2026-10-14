# FM Runner Manager roadmap

## Phase 0 — Safe foundation (current)
- [x] Private repository and isolated feature branch.
- [x] Kotlin Android project scaffold.
- [x] Four local account-label slots.
- [x] Device diagnostics for Android/API/ABI, storage, and available RAM.
- [x] Copyable diagnostics report.
- [x] Local metadata-only audit log for label changes and report copies.
- [x] No credentials, network access, package installation, or runner control in the starter app.

## Phase 1 — Local diagnostics and package readiness
- [x] Document read-only checks and distinguish Android, Termux, and proot/Linux environments.
- [x] Define safe on-demand package-manager behaviour: detect first, explain, ask approval, install, verify, audit.
- [ ] Define the exact supported execution environments based on compatibility evidence.
- [ ] Detect tools only in explicitly supported locations; show version and evidence for each detection.
- [ ] Mark unavailable tools as “not detected”, never silently install them.
- [ ] Build the package catalogue and per-package details screen (source, version, size if known, storage estimate, permissions, compatibility, rollback notes).
- [ ] Add explicit one-package-at-a-time approval and install/verify/result flow.
- [ ] Implement and test a trusted install adapter for each supported environment before enabling its Install button.
- [ ] Add timestamped export that avoids credentials and sensitive project content.

Package manager design: see [PACKAGE-MANAGER-DESIGN.md](PACKAGE-MANAGER-DESIGN.md). The design is documented; the app does not install packages yet.

## Phase 2 — Account isolation
- [x] Document the safe GitHub/DroidRunner adapter boundary and compatibility gates (design only; no live integration enabled).
- [x] Add adapter contracts and a local policy evaluator; missing/invalid temperature data must deny when a temperature ceiling is configured.
- [ ] Add automated policy-gate tests once an approved test dependency/setup is available.
- [ ] Add a GitHub authorization flow using a supported secure OAuth/device flow.
- [ ] Store authorization material only in Android Keystore-backed storage.
- [ ] Keep one credential vault and runner work/config directory per identity.
- [ ] Display repository permissions and connection state separately from runner state.
- [ ] Never accept or log runner registration tokens in plain text.

## Phase 3 — Runner integration
- [ ] Identify and verify the actual DroidRunner local control interface; GitHub REST API alone cannot start/stop the local process.
- [ ] Confirm GitHub-supported runner architecture and compatibility with the actual Android execution environment.
- [ ] Register each runner explicitly to the chosen repository or organization.
- [ ] Keep runner registrations separate; do not copy one account's credentials to another.
- [ ] Implement online/offline status and job metadata with rate limits.
- [ ] Add manual start/stop and recovery controls with confirmation and audit events.

## Phase 4 — Build and release
- [x] Build debug APK in GitHub Actions on the feature branch (run 38553960072, commit e823caa0637533766c229610e8ec85a969bdc62c).
- [ ] Rebuild and verify the fail-closed policy correction.
- [ ] Add reproducible Gradle wrapper files and verify the build from a clean checkout.
- [ ] Test install, launch, rotation, low storage, and interrupted network on the Nubia Neo 2 device.
- [ ] Sign release APK only with a user-controlled signing key that is never committed.
- [ ] Publish a release only after explicit owner approval.

## Non-goals
- Bypassing GitHub billing or account permissions.
- Sharing credentials across identities.
- Automatically running privileged shell commands.
- Unattended package installation or workflow dispatch.
- Changing other repositories as part of this app.
