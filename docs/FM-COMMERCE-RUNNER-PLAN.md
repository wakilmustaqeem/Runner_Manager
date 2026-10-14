# FM Commerce Runner Plan

## Purpose
FM Runner Manager is being developed in the private `wakilmustaqeem/Runner_Manager` repository to support safe, low-cost maintenance and build/deploy tasks for the separate FM Commerce platform:

- Target repository: `fmmustaqeem/fm-commerce-platform`
- Intended recipient/operating identity: `fmmustaqeem`
- Runner Manager development identity: `wakilmustaqeem`

These identities and repositories must remain separate. This plan does not grant access, transfer ownership, register a runner, or deploy the commerce platform.

## First use case
Prepare a runner environment that can inspect the FM Commerce project's actual requirements and run only approved tasks, such as dependency checks, tests, and a controlled deployment workflow when the deployment method is confirmed.

Do not assume that PHP 8.3 is required until the target repository's runtime constraints and hosting environment are verified. PHP compatibility may be controlled by `composer.json`, framework requirements, and the hosting provider. Do not install PHP on the Android phone merely because PHP appears in the package catalogue.

## Environment separation
- **Runner Manager Android app:** control/diagnostics interface; no shell installer or runner registration is implemented yet.
- **Android phone / Termux / proot:** distinct environments. PHP support must be detected and verified within the explicitly selected environment.
- **GitHub-hosted Actions:** convenient for a single controlled check, but may consume Actions minutes; no workflow should run automatically on every push by default.
- **Self-hosted runner:** a separate machine/runtime that must be confirmed compatible and explicitly registered. Do not assume a native Android app is itself a supported GitHub Actions runner host.
- **FM Commerce hosting:** the deployment target is separate from the runner. Hosting credentials must be stored as protected secrets only after the deployment route is verified.

## Safe sequence
1. Read-only inspect the target repository's runtime files and current deployment method.
2. Identify the required PHP version, extensions, Composer version, and OS/architecture from project evidence.
3. Select the actual execution environment and verify compatibility.
4. Create a branch and restore point before any project change.
5. Run one manually approved, low-cost validation job; keep logs free of secrets and customer data.
6. Review results and deployment permissions before any deployment.
7. Offer the completed Runner Manager to the intended recipient only after functionality and security are tested. Do not transfer repositories or change access without explicit approval.

## Package manager rule
For PHP 8.3 and every other package: detect first, explain source/version/storage/permissions, ask for approval, install only through a trusted supported adapter, verify the installed version, and record metadata-only audit results. Until an adapter is implemented and tested, show the package as detection-only / installation unsupported.
