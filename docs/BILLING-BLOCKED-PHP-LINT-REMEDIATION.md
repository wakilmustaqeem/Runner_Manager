# Billing-blocked PHP Lint: Runner Manager Remediation

## Incident
The FM Commerce PHP Lint job reported:
"The job was not started because recent account payments have failed or your spending limit needs to be increased."

This is a GitHub Actions billing/spending-limit gate. It is not evidence that PHP syntax failed: the job did not start, so no lint result was produced.

## Objective
Use FM Runner Manager to support a safe, low-cost alternative for validating PHP in the separate `fmmustaqeem/fm-commerce-platform` repository, while keeping the `wakilmustaqeem` development identity and `fmmustaqeem` commerce identity separate.

## Important limits
- This Android app scaffold does not yet execute shell commands, install PHP, connect to GitHub, or register/manage a runner.
- Do not try to fix an account billing restriction by changing code, bypassing GitHub billing, or switching credentials silently.
- Do not route this job through an existing runner belonging to a different project/account without explicit approval.
- Do not create an always-on or push-triggered Actions workflow that could repeatedly consume minutes.

## Safe remediation path
1. **Read-only diagnosis:** verify the original workflow's trigger, PHP version, target repository permissions, and whether the billing restriction remains. A billing-blocked run must be labelled `BLOCKED_BEFORE_START`, not `LINT_FAILED`.
2. **Choose an approved execution host:** identify a supported Linux host where PHP CLI 8.3 can run. Native Android itself is not assumed to be a supported GitHub Actions runner host; Termux/proot is a separate environment and requires compatibility validation.
3. **Implement a local lint adapter in Runner Manager:** initially detection-only. Check the selected environment, `php -v`, `php -l`, required extensions, and repository path without installing anything or printing secrets.
4. **Add explicit approvals:** require the owner to select the repository, branch/commit, environment, PHP version, and exact lint action before any job executes.
5. **Keep a clear result taxonomy:** `PASS`, `LINT_FAILED` (only after PHP actually runs and reports syntax errors), `BLOCKED_BEFORE_START` (billing/permissions/runner unavailable), and `UNVERIFIED`.
6. **Verify once with minimal cost:** use one manually approved test. No automatic retries, dependency installation, deployment, or Actions dispatch by default.
7. **Only later consider self-hosted Actions integration:** after the host is proven compatible, credentials are isolated, registration is explicitly approved, and job scope is restricted. Never expose registration tokens in logs.

## Current status
This document records the remediation design only. It does not remove GitHub billing restrictions, run PHP lint, install PHP 8.3, register a runner, or deploy FM Commerce. The current Runner Manager package catalogue remains detection-only until a trusted adapter and tests are implemented.
