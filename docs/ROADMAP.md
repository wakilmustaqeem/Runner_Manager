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
- [ ] Define the exact supported execution environments based on compatibility evidence.
- [ ] Detect tools only in explicitly supported locations; show version and evidence for each detection.
- [ ] Mark unavailable tools as “not detected”, never silently install them.
- [ ] Add a per-package approval screen showing source, size if known, permissions, and rollback notes.
- [ ] Add timestamped export that avoids credentials and sensitive project content.

## Phase 2 — Account isolation
- [ ] Add a GitHub authorization flow using a supported secure OAuth/device flow.
- [ ] Store authorization material only in Android Keystore-backed storage.
- [ ] Keep one credential vault and runner work/config directory per identity.
- [ ] Display repository permissions and connection state separately from runner state.
- [ ] Never accept or log runner registration tokens in plain text.

## Phase 3 — Runner integration
- [ ] Confirm GitHub-supported runner architecture and compatibility with the actual Android execution environment.
- [ ] Register each runner explicitly to the chosen repository or organization.
- [ ] Keep runner registrations separate; do not copy one account's credentials to another.
- [ ] Implement online/offline status and job metadata with rate limits.
- [ ] Add manual start/stop and recovery controls with confirmation and audit events.

## Phase 4 — Build and release
- [ ] Add reproducible Gradle wrapper files and an Android build workflow.
- [ ] Build debug APK on a confirmed compatible runner.
- [ ] Test install, launch, rotation, low storage, and interrupted network.
- [ ] Sign release APK only with a user-controlled signing key that is never committed.
- [ ] Publish a release only after explicit owner approval.

## Non-goals
- Bypassing GitHub billing or account permissions.
- Sharing credentials across identities.
- Automatically running privileged shell commands.
- Unattended package installation or workflow dispatch.
- Changing other repositories as part of this app.
