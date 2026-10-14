# FM Runner Manager

**Private, local-first Android app** for the eventual monitoring and management of self-hosted GitHub Actions runners on an Android device.

## Current foundation

- Four separately editable local account labels.
- Android version/API, supported ABI, internal storage, available RAM, and external-storage-state diagnostics.
- Copy diagnostics report to clipboard by explicit user action.
- Local metadata-only audit log for account-label changes and report copies.
- No network calls, GitHub credentials, package installation, runner registration, or job dispatch in this scaffold.

## Purpose

Provide one mobile dashboard for up to four separately authorized GitHub identities, with clear isolation between accounts, repositories, runner registrations, credentials, and audit events.

## Safety rules

- **Default deny:** no GitHub action, account connection, package installation, workflow dispatch, or runner registration without explicit user action and authorization.
- **Account isolation:** never share tokens, runner registration tokens, work directories, or configuration between identities.
- **Least privilege:** use fine-grained credentials with the smallest necessary scope; do not store personal access tokens in source code, logs, or plain-text preferences.
- **No billing bypass:** the app cannot bypass GitHub billing, spending limits, repository permissions, or organization policy.
- **Package safety:** inspect the environment first. Do not blindly run `apt`, `dpkg`, root commands, or unattended installers. Explain required permissions and obtain approval before installation.
- **Audit and recovery:** record action metadata without logging secrets or project payloads. Preserve a recovery path before configuration changes.
- **Resource-aware:** prefer one active job at a time initially; provide low-data mode and avoid automatic reruns.

## Four-account model

The four slots are local display labels, not pre-authorized identities. Each account must be connected by the owner and verified independently. No account is inferred from another account's login session.

## Planned modules

1. Runner dashboard: Online / Offline / Busy / Error, last check, latest job result.
2. Account & repository registry: four isolated profiles and explicit permission status.
3. Device diagnostics and report export.
4. Package readiness: detect required tools and versions; report missing items before proposing installation.
5. Runner setup guide and registration checks without exposing registration tokens.
6. Workflow activity: job status and links, with manual refresh and opt-in dispatch.
7. Audit / restore: metadata-only log and configuration backup before changes.
8. English-first interface with Urdu language option.

## Verification status

This is a **foundation scaffold, not a production runner manager and not a verified APK**. Android build/runtime testing has not yet been confirmed. GitHub OAuth, Android Keystore-backed credential storage, live runner monitoring, package installation, and APK signing remain unimplemented. Do not paste secrets into Issues, commits, or chat.

## Development approach

- Kotlin + Android Gradle Plugin.
- Build and verify on a separate feature branch before any merge to `main`.
- Never modify other repositories as part of this project.
- Do not claim a runner is online or a package is installed unless a live check confirms it.
