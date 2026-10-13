# FM Runner Manager

**Private, local-first Android app** for monitoring and managing self-hosted GitHub Actions runners on the user's Android device.

## Purpose

Provide one mobile dashboard for up to four *separately authorized* GitHub identities, with clear isolation between accounts, repositories, runner registrations, credentials, and audit events.

## Safety rules

- **Default deny:** no GitHub action, account connection, package installation, workflow dispatch, or runner registration without explicit user action and authorization.
- **Account isolation:** never share tokens, runner registration tokens, work directories, or configuration between identities.
- **Least privilege:** use fine-grained credentials with the smallest necessary scope; do not store personal access tokens in source code, logs, or plain-text preferences.
- **No billing bypass:** the app cannot bypass GitHub billing, spending limits, repository permissions, or organization policy.
- **Package safety:** inspect the environment first. Do not blindly run `apt`, `dpkg`, root commands, or unattended installers. Explain required permissions and obtain approval before installation.
- **Audit and recovery:** record action metadata (account alias, repository, action, time, result) without logging secrets or project payloads. Preserve a recovery path before configuration changes.
- **Resource-aware:** prefer one active job at a time initially; provide low-data mode and avoid automatic reruns.

## Four-account model

The four slots are aliases, not pre-authorized identities. Each account must be connected by the owner and verified independently. No account is inferred from another account's login session.

## Planned modules

1. Runner dashboard: Online / Offline / Busy / Error, last check, latest job result.
2. Account & repository registry: four isolated profiles and explicit permission status.
3. Device diagnostics: Android version, ABI, storage, battery, network, and detected tools.
4. Package readiness: detect required tools and versions; report missing items before proposing installation.
5. Runner setup guide: registration steps and checks without exposing registration tokens.
6. Workflow activity: job status and links, with manual refresh and opt-in dispatch.
7. Audit / restore: metadata-only log and configuration backup before changes.
8. English-first interface with Urdu language option.

## Current implementation status

The initial branch is a **foundation scaffold**, not a production runner or a verified APK. GitHub OAuth, secure token storage, live runner monitoring, package installation, and APK signing must be implemented and tested separately. Do not paste secrets into Issues, commits, or chat.

## Development approach

- Kotlin + Android Gradle Plugin.
- Build and verify on a separate feature branch before any merge to `main`.
- Never modify other repositories as part of this project.
- Do not claim a runner is online or a package is installed unless a live check confirms it.
