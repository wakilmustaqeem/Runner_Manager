# Runtime preflight and manifest-signature milestone

Branch: `feat/runner-manager-active-runner-implementation-v1`

This milestone adds fail-closed preflight checks and a detached manifest-signature verifier. It is intentionally not a runtime downloader or runner launcher.

## Implemented

- Requires the runtime manifest URL to use HTTPS.
- Requires an explicitly configured trusted signing key; the build no longer defaults to DroidRunner's public signing key.
- Restricts the current packaged-runtime target to `arm64-v8a`.
- Requires a positive archive-size estimate and a storage budget of at least three times the archive size before installation is considered ready.
- Provides a streaming SHA-256 helper and strict 64-hex digest comparison for a future installer.
- Verifies detached ECDSA/SHA-256 signatures over the exact manifest byte array, before any parsing is performed by a caller.
- Restricts configured EC public keys to a 256-bit prime-field curve and rejects malformed/empty input.
- Adds a pure archive-entry and symlink-target path policy that rejects absolute paths, Windows-style paths, traversal segments, and symlink targets that escape the archive root.
- Adds an app-private runtime artifact stager: it verifies the detached signature before parsing JSON, enforces manifest expiry/version/size limits, rejects redirects and non-HTTPS URLs, downloads to a temporary file, checks exact size and SHA-256, and only then renames the artifact to a staged filename.
- Adds unit tests for trust/preflight failures, digest matching, valid signature, tampered manifest bytes, malformed signature input, archive traversal, and symlink escape rejection.

## Manifest signature contract

- Public key: Base64-encoded X.509 SubjectPublicKeyInfo DER containing a 256-bit EC public key.
- Signature: Base64-encoded ASN.1 DER ECDSA signature created with SHA-256.
- Signed data: exact bytes returned for the manifest; verify before UTF-8 decoding or JSON parsing.
- Trust anchor: an FM-controlled public key. The matching private key must be kept outside the app/repository and used only by a controlled release process.
- The verifier is a primitive, not a complete key-rotation or manifest-schema implementation. The installer must define canonical release metadata and enforce expiry/version/rollback rules before use.

## Deliberately blocked / not implemented

- The new stager can download an archive only when explicitly called with a trusted public key and signed manifest/signature; it is not wired to the UI and has not been run. No extraction or activation is implemented.
- No production FM signing key, signed manifest, or runtime archive is configured or published.
- No PRoot binary or loader is packaged in FM's `jniLibs/arm64-v8a`.
- No runner configuration, registration token exchange, foreground service, actual Start/Stop, boot recovery, or process supervision is wired.
- No Build, Actions job, runtime download, or live registration was run as part of this change.

## Security decision

DroidRunner's runtime signature authenticates artifacts published by DroidRunner, not artifacts controlled by FM. Reusing that public key would make FM trust a third party to supply the Linux environment in which repository workflows execute. FM must generate and protect its own signing key, publish its own runtime manifest and archive, and compile only the corresponding public key into its app before enabling installation.

## Acceptance criteria for the next step

1. Choose and pin the PRoot source commit and required loader files; review GPL-2.0 / LGPL-3.0 obligations and ship corresponding source/patches.
2. Finalize and review the signed manifest schema, release key management, expiry and anti-rollback policy; the stager currently accepts schemaVersion, version, expiresAtEpochSeconds, archiveUrl, archiveBytes, and archiveSha256.
3. Integrate the path policy into a real extractor that never follows archive-created symlinks during extraction; reject unexpected rootfs paths and missing runner executables.
4. Keep the previous runtime intact until validation succeeds; recover safely after interrupted activation.
5. Run one explicitly approved debug build and unit-test job, then verify on the target Android 13 arm64 device before enabling registration or Start/Stop.
