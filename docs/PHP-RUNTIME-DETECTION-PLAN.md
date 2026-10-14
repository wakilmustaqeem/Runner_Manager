# FM Commerce PHP Runtime Detection Plan

## Purpose
Prepare Runner_Manager to diagnose the PHP runtime needed to validate `fmmustaqeem/fm-commerce-platform` without assuming that PHP 8.3 is required or installed.

## Current verified boundary
- Runner_Manager is currently a native Android/Kotlin foundation.
- Its current PHP lint status screen records a user-selected classification only.
- It does not execute PHP, install packages, connect to GitHub, register a runner, or dispatch jobs.
- The original GitHub Actions message said the job could not start because of account payment failure or a spending limit. That is a pre-start billing gate, not a PHP syntax result.
- A real `PASS` requires PHP to execute against the target project and return a successful lint result. A blocked job must never be recorded as a pass.

## Detection-first sequence
1. Identify the environment: Android app, Android/Termux shell, Linux host, GitHub-hosted Actions, or an already registered self-hosted runner.
2. Check whether a trusted local command adapter exists. If it does not, report `UNSUPPORTED`; do not emulate a successful check.
3. If the user explicitly approves a supported environment check, detect the PHP executable and report its version and path. Do not install anything as part of detection.
4. Verified target-repository evidence (read-only): `fmmustaqeem/fm-commerce-platform` README declares `PHP 8.3+`, and `.github/workflows/php-lint.yml` pins `php-version: '8.3'`. The workflow runs `php -l` over `fm-marketplace-core/**/*.php`. The same README's Project Identity section names `wakilmustaqeem/fm-commerce-platform`, which differs from the actual repository being inspected; keep accounts separate and flag this documentation inconsistency for owner review rather than changing it automatically. `composer.json`, `composer.lock`, and `.php-version` were not found at repository root. This evidence supports PHP 8.3 for the existing lint workflow, but does not prove PHP 8.3 is installed on the phone or any self-hosted runner.
5. Only after the environment and project requirement are verified, propose the smallest test that can run `php -l` on relevant PHP files.
6. Keep results distinct:
   - `PASS`: PHP actually ran and all targeted syntax checks succeeded.
   - `LINT_FAILED`: PHP actually ran and reported syntax errors.
   - `BLOCKED_BEFORE_START`: billing, permission, missing runner, or environment gate prevented execution.
   - `UNSUPPORTED`: no safe execution adapter is implemented for the selected environment.
   - `UNVERIFIED`: no reliable execution result exists.
7. Do not automatically retry, install packages, change hosting, deploy, or merge code. Require explicit approval for state-changing operations.

## Package-manager policy
- Detection only by default.
- Package installation is not implemented in the current app.
- Before any installation, show the package name, source, environment, expected impact, and whether administrator/root access is required; then request explicit approval.
- Never run package installation commands through an arbitrary shell or from untrusted repository content.
- Record audit metadata only (timestamp, action, environment label, outcome); do not log tokens, secrets, source file contents, or personal project data.

## Low-cost verification plan
1. Review the existing manual Android build workflow and current branch diff first.
2. Do not start GitHub Actions until the user approves the run and any applicable billing/spending gate is understood.
3. Prefer a verified available local runner or already-approved free environment only after confirming that it can safely execute the required checks.
4. Keep the current feature branch and backup branch. Do not merge the open pull request or change `main` without approval.

## Acceptance criteria
- Runtime status is detected rather than guessed.
- Missing PHP and missing execution adapter are reported honestly.
- PHP version requirements are grounded in the project or host documentation.
- A blocked job is never reported as a syntax failure or a pass.
- No package is installed and no deployment is performed without explicit approval.
