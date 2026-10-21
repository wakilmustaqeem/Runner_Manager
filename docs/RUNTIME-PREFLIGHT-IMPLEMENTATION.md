# Runtime preflight implementation milestone

Branch: `feat/runner-manager-active-runner-implementation-v1`

This milestone adds a fail-closed preflight utility. It is intentionally not a runtime downloader or runner launcher.

## Implemented

- Requires the runtime manifest URL to use HTTPS.
- Requires an explicitly configured trusted signing key; the build no longer defaults to DroidRunner's public signing key.
- Restricts the current packaged-runtime target to `arm64-v8a`.
- Requires a positive archive-size estimate and a storage budget of at least three times the archive size before installation is considered ready.
- Provides a streaming SHA-256 helper and strict 64-hex digest comparison for use by a future signed-manifest installer.
- Adds unit tests for missing trust, non-HTTPS manifests, unsupported ABI, and digest matching.

## Deliberately blocked / not implemented

- No runtime download or extraction is started by this class.
- No detached ECDSA signature verification is implemented in FM yet.
- No PRoot binary or loader is packaged in FM's `jniLibs/arm64-v8a`.
- No runner configuration, registration token exchange, foreground service, actual Start/Stop, boot recovery, or process supervision is wired.
- No Build, Actions job, runtime download, or live registration was run as part of this change.

## Security decision

DroidRunner's runtime signature authenticates artifacts published by DroidRunner, not artifacts controlled by FM. Reusing that public key would make FM trust a third party to supply the Linux environment in which repository workflows execute. FM must generate and protect its own signing key, publish its own runtime manifest and archive, and compile only the corresponding public key into its app before enabling installation.

## Acceptance criteria for the next step

1. Choose and pin the PRoot source commit and required loader files; review GPL-2.0 / LGPL-3.0 obligations and ship corresponding source/patches.
2. Implement ECDSA P-256 detached signature verification over the exact manifest bytes before parsing it.
3. Download into app-private staging with size limits, resumable-download integrity, and SHA-256 validation.
4. Reject archive traversal, symlinks escaping staging, unexpected rootfs paths, and missing runner executables.
5. Keep the previous runtime intact until validation succeeds; recover safely after interrupted activation.
6. Run one explicitly approved debug build and unit-test job, then verify on the target Android 13 arm64 device before enabling registration or Start/Stop.
