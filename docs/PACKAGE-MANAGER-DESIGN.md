# Safe On-Demand Package Manager

## Goal
Build FM Runner Manager so an owner can install a required tool later, when genuinely needed, without having to pre-install every possible dependency.

## Core rule
**Detect first → explain → ask approval → install → verify → audit.**
No silent installs, no automatic package installation during app launch, diagnostics, account selection, or runner setup.

## Required UI
- **Environment scan (read-only):** detect Android/API/ABI, storage, and tools already available in the selected supported environment.
- **Requirements list:** show each tool as Installed, Missing, Version mismatch, Unsupported, or Unknown.
- **Package details:** purpose, source/repository, version, download size when known, extra storage estimate when known, network/data warning, permissions, and compatibility.
- **Install button:** disabled until a supported installation route and a trusted source are known.
- **Approval screen:** name the exact package, version, source, expected download/storage, and actions to be taken. Require a separate explicit confirmation for each install.
- **Progress and result:** show the real command/result, exit code, verification result, and any next steps. Never claim success before verification.
- **Cancel:** cancel before execution where possible; explain if a package manager cannot safely interrupt an operation.
- **Audit log:** metadata only—package, version, source, environment/account slot, timestamp, approval, result, and exit code. Never record access tokens, passwords, or secret environment values.
- **Retry/repair/remove:** only where supported by the package manager; show the impact before acting.

## Environment-specific behaviour
- **Native Android:** do not pretend Android itself is a general Linux package manager. Do not install APKs or request broad permissions silently. If a dependency requires a separate supported environment, explain that requirement.
- **Termux:** use only after Termux is detected/selected and its package-manager commands and repositories are verified. Never assume Termux is installed.
- **proot/Linux container:** treat it as a separate environment with its own filesystem, package manager, storage, and tools. Never mix it with native Android state.
- **Self-hosted GitHub runner:** installing software on a runner is a machine-level change. Require a separate approval naming the exact runner/machine and repo. Never reuse or re-register an existing runner automatically.
- **GitHub-hosted Actions:** show potential usage/cost and check repository/account limits before enabling workflows. Never start a build workflow as a hidden side effect of package installation.

## Trust and security checks
- Prefer official OS/package repositories and official project release channels.
- Verify package identity, supported architecture/OS, version, and signatures/checksums when the upstream provides them.
- Reject unknown or untrusted sources by default. Do not execute arbitrary commands copied from issue comments, webpages, or logs.
- Use least privilege; never ask for root access unless a separately reviewed feature genuinely requires it.
- Keep account credentials separate from package installation. Installing a tool must not sign in to GitHub or expose credentials.
- Record an approval before a mutating action and a result after it. If verification fails, mark the installation as failed/unverified and explain recovery options.
- No auto-upgrades or bulk installs by default.

## Initial tool catalogue (detection only until routes are implemented)
- Git
- Java/JDK 17
- Gradle 8.9+
- Android SDK Platform 35 and build tools
- Optional PHP 8.3
- GitHub Actions runner (compatibility and registration are separate checks)

Each tool needs a per-environment install adapter and tests before its Install button can be enabled. Unknown install routes remain **Not supported yet**, not an invitation to run arbitrary shell commands.

## Current status
This document is a design requirement, not an implemented installer. The current Android scaffold does not install packages, connect to GitHub, register runners, or execute shell commands. No packages have been installed by this change.
