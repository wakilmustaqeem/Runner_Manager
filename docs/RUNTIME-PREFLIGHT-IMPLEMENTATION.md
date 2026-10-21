# Runtime preflight and artifact staging milestone

Branch: `feat/runner-manager-active-runner-implementation-v1`

This milestone adds fail-closed runtime preflight, detached manifest-signature verification, artifact staging, and a transactional metadata pointer. It does not extract or execute a runtime and does not launch a GitHub Actions runner.

## Implemented

- Requires the runtime manifest URL to use HTTPS.
- Requires an explicitly configured trusted signing key; the build no longer defaults to DroidRunner's public signing key.
- Restricts the current packaged-runtime target to `arm64-v8a`.
- Requires a positive archive-size estimate and a storage budget of at least three times the archive size before installation is considered ready.
- Provides streaming SHA-256 and strict digest comparison.
- Verifies detached ECDSA/SHA-256 signatures over exact manifest bytes before parsing.
- Restricts configured EC public keys to a 256-bit prime-field curve.
- Provides archive-entry and symlink-target path-policy checks against absolute paths, traversal, Windows paths, and symlink escape.
- Stages a runtime archive only after signed-manifest verification, expiry/version/size checks, HTTPS-only no-redirect download, exact byte-count validation, and SHA-256 verification.
- Adds `RuntimeActivationStore`, which verifies the staged archive again and atomically switches a small app-private JSON metadata pointer with a best-effort previous-pointer rollback if the pointer commit fails.
- Adds unit-test source for preflight, digest/signature verification, archive path policy, and metadata-pointer integrity/path restrictions.

## Manifest contract

The stager currently expects a signed UTF-8 JSON object with `schemaVersion`, `version`, `expiresAtEpochSeconds`, `archiveUrl`, `archiveBytes`, and `archiveSha256`. The detached signature is Base64 DER ECDSA/SHA-256 over the exact manifest bytes. The public key is Base64 X.509 SubjectPublicKeyInfo DER containing a 256-bit EC public key.

The trust anchor must be an FM-controlled public key. The matching private key must remain outside the app and repository and be used only by a controlled release process.

## Important boundary

`RuntimeActivationStore` activates **metadata only**: `active-runtime.json` identifies a staged archive and digest. It does not unpack files, change executable permissions, install PRoot, start a process, or prove that a runner is online. Do not expose this pointer as a connected/active runner state.

## Not implemented / blocked

- No production FM signing key, signed manifest, or runtime archive is configured or published.
- No PRoot binary or loader is packaged in `jniLibs/arm64-v8a`.
- The archive path policy is not integrated into a real extraction implementation; extraction must avoid following archive-created symlinks.
- No runner configuration, registration token exchange, foreground service, actual Start/Stop, boot recovery, or process supervision is wired.
- No Build, Actions job, runtime download, or live registration was run.

## Security decision

DroidRunner's runtime signature authenticates artifacts published by DroidRunner, not artifacts controlled by FM. Reusing that public key would make FM trust a third party to supply the Linux environment in which repository workflows execute. FM must control its own signing key and runtime release process before enabling installation.

## Next acceptance gates

1. Choose and pin the PRoot source commit and loader files; review GPL-2.0 / LGPL-3.0 obligations and ship corresponding source/patches.
2. Finalize and review the manifest schema, release key management, expiry, and anti-rollback policy.
3. Integrate path checks into a real safe extractor; validate expected rootfs paths and runner executables.
4. Preserve the previous runtime until extraction and validation succeed; test recovery after interrupted activation.
5. Obtain explicit approval for one debug build/unit-test job, then verify on the target Android 13 arm64 device before enabling registration or Start/Stop.
