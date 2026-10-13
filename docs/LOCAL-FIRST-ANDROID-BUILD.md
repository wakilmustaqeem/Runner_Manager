# Local-First Android Build Preflight

## Purpose

Prefer a local build before using GitHub-hosted Actions. This guide only checks existing tools first; it does not install packages, register a runner, change account assignments, or start a GitHub Actions job.

Repository: `wakilmustaqeem/Runner_Manager`  
Working branch: `feat/fm-runner-manager-foundation-2026-10-13`

## Required build environment

- JDK 17
- Gradle 8.9 available on PATH (this repository currently has no checked-in Gradle wrapper)
- Android SDK with platform 35 and Build Tools 35.0.0
- Enough free memory and disk space for an Android build

The project config currently targets compile SDK 35 and Java/Kotlin 17. These are configuration facts, not proof that the project compiles.

## Step 1 — Inspect existing tools only

### Linux / macOS shell

```sh
java -version
gradle --version
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$ANDROID_HOME" "$ANDROID_SDK_ROOT"
```

### Windows PowerShell

```powershell
java -version
gradle --version
"ANDROID_HOME=$env:ANDROID_HOME"
"ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
```

If a command is unavailable, stop here. Do not install anything as part of this preflight; first choose and approve the intended local setup.

## Step 2 — Confirm SDK components

Use the SDK manager already present in the installed Android SDK. Do not accept or install missing components without approval.

```sh
sdkmanager --list_installed
```

Confirm that Android platform 35 and Build Tools 35.0.0 are already installed.

## Step 3 — Build only when prerequisites are present

From the repository root, and only after confirming the prerequisites above:

```sh
gradle --no-daemon assembleDebug testDebugUnitTest
```

A successful result must be supported by the actual command exit status and logs. Do not infer build success from a green CodeRabbit review or from the existence of this workflow file.

Expected debug APK path if the build succeeds:

```
app/build/outputs/apk/debug/
```

## Safety boundaries

- Do not switch branches, reset, revert, merge, or modify `main` as part of this preflight.
- Do not start GitHub Actions or spend hosted-runner minutes as a substitute for local checks.
- Do not register or reassign the DroidRunner device. Its existing FM Commerce assignment remains separate.
- Do not include tokens, passwords, signing keys, personal data, or project secrets in logs or reports.
- If local prerequisites are missing, report the exact missing prerequisite and stop; request approval before installing tools or using hosted Actions.
