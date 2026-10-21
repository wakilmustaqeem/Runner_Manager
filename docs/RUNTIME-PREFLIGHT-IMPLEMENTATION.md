# Runtime preflight, staging, and extraction-safety milestone

Branch: `feat/runner-manager-active-runner-implementation-v1`

This milestone adds fail-closed runtime preflight, detached manifest-signature verification, artifact staging, metadata-pointer activation, and an extraction sink for a future audited archive parser. It does not unpack an archive format itself or launch a GitHub Actions runner.

## Implemented

- Requires the runtime manifest URL to use HTTPS.
- Requires an explicitly configured trusted signing key; the build no longer defaults to DroidRunner's public signing key.
- Restricts the current packaged-runtime target to `arm64-v8a`.
- Requires positive archive size and app-private storage headroom.
- Provides streaming SHA-256 and strict digest comparison.
- Verifies detached ECDSA/SHA-256 signatures over exact manifest bytes before parsing.
- Provides archive-entry and symlink-target path-policy checks.
- Stages an archive only after signed-manifest verification, expiry/version/size checks, HTTPS-only no-redirect download, exact byte-count validation, and SHA-256 verification.
- Adds `RuntimeActivationStore`, which re-hashes a staged archive and switches an app-private metadata pointer with best-effort rollback of the prior pointer if the pointer switch fails.
- Adds `SafeRuntimeExtractor`, a fail-closed extraction sink for a future archive parser. It accepts only regular files and directories, rejects links and special files, rejects traversal/absolute paths, prevents overwrites, verifies declared sizes, and enforces entry/per-file/total byte limits.
- Adds test source for preflight, digest/signature verification, path policy, metadata-pointer integrity, and extraction-sink boundaries.

## Manifest contract

The stager expects signed UTF-8 JSON with `schemaVersion`, `version`, `expiresAtEpochSeconds`, `archiveUrl`, `archiveBytes`, and `archiveSha256`. The detached signature is Base64 DER ECDSA/SHA-256 over exact manifest bytes. The public key is Base64 X.509 SubjectPublicKeyInfo DER containing a 256-bit EC public key. FM must own the trust anchor; its private key must stay outside the app and repository.

## Critical limitations

- `SafeRuntimeExtractor` is a sink, **not a tar/zstd parser**. It has not been wired to any archive format or downloader. Its intentional rejection of symbolic/hard links means it cannot yet unpack many Linux root filesystems (which commonly need symlinks); do not feed a real PRoot archive to it as-is.
- It does not preserve executable permissions, ownership, or special file metadata.
- `RuntimeActivationStore` switches metadata only. It does not unpack or execute a runtime and must not be treated as proof that a runner is online.
- No production FM signing key, signed manifest/archive, PRoot binary or loader is configured or packaged.
- No runner configuration/token exchange, foreground service, actual Start/Stop, boot recovery, or process supervision is wired.
- No Build, Actions job, runtime download, or live registration was run.

## Security decision

DroidRunner's runtime signature authenticates artifacts published by DroidRunner, not artifacts controlled by FM. Reusing that public key would make FM trust a third party to supply the Linux environment in which repository workflows execute. FM must control its own signing key and release process before enabling installation.

## Next acceptance gates

1. Choose and pin the PRoot source commit and loader files; review GPL-2.0 / LGPL-3.0 obligations and ship corresponding source/patches.
2. Finalize the manifest schema, release key management, expiry, and anti-rollback policy.
3. Select/audit an archive parser and integrate it with a link-safe extractor that supports the exact runtime archive format, required symlinks, executable bits, and expected rootfs paths without following archive-created links.
4. Preserve the previous runtime until extraction and validation succeed; test recovery after interrupted activation.
5. Obtain explicit approval for one debug build/unit-test job, then verify on the target Android 13 arm64 device before enabling registration or Start/Stop.
