# Runtime preflight, staging, and extraction-safety milestone

Branch: `feat/runner-manager-active-runner-implementation-v1`

This milestone adds fail-closed runtime preflight, detached manifest-signature verification, artifact staging, metadata-pointer activation, bounded TAR.GZ extraction, and transactional runtime-directory installation. It still does not launch or register a GitHub Actions runner.

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
- Adds `RuntimeTarGzExtractor` using Apache Commons Compress for streaming TAR.GZ decoding. It enforces path/entry/byte limits, blocks hard links and special entries, maps only in-rootfs symlinks to relative guest paths, preserves executable bits, rejects entries beneath earlier symlinks, and attempts rollback of created paths on failure.
- Adds `RuntimeArchiveInstaller`, which rechecks staged archive size and SHA-256, extracts to a fresh sibling staging directory, requires a real `rootfs` directory, refuses to overwrite an existing runtime directory, and publishes the complete extraction only after validation.
- Adds test source for preflight, digest/signature verification, path policy, metadata-pointer integrity, TAR.GZ extraction, and installer success/failure boundaries. These tests have not been run.

## Manifest contract

The stager expects signed UTF-8 JSON with `schemaVersion`, `version`, `expiresAtEpochSeconds`, `archiveUrl`, `archiveBytes`, and `archiveSha256`. The detached signature is Base64 DER ECDSA/SHA-256 over exact manifest bytes. The public key is Base64 X.509 SubjectPublicKeyInfo DER containing a 256-bit EC public key. FM must own the trust anchor; its private key must stay outside the app and repository.

## Critical limitations

- `SafeRuntimeExtractor` remains a generic regular-file-only sink and is not the installer path for TAR.GZ runtime bundles. The TAR.GZ parser is now present, but the combined code remains uncompiled and untested.
- TAR.GZ extraction does not preserve ownership, timestamps, capabilities, device nodes, or arbitrary special metadata. Hard links and special entries are rejected. Symlink handling is restricted to paths within `rootfs` and needs testing against the exact upstream archive.
- `RuntimeArchiveInstaller` validates the archive digest again and publishes an extracted directory, but it is not connected to the UI, `RuntimeActivationStore`, or a PRoot launch adapter.
- `RuntimeActivationStore` switches metadata only. It does not unpack or execute a runtime and must not be treated as proof that a runner is online.
- No production FM signing key, signed manifest/archive, PRoot binary or loader is configured or packaged.
- No runner configuration/token exchange, foreground service, actual Start/Stop, boot recovery, or process supervision is wired.
- No Build, Actions job, runtime download, or live registration was run. Test changes are source-only and remain unverified until an approved test run.

## Security decision

DroidRunner's runtime signature authenticates artifacts published by DroidRunner, not artifacts controlled by FM. Reusing that public key would make FM trust a third party to supply the Linux environment in which repository workflows execute. FM must control its own signing key and release process before enabling installation.

## Next acceptance gates

1. Choose and pin the PRoot source commit and loader files; review GPL-2.0 / LGPL-3.0 obligations and ship corresponding source/patches.
2. Finalize the manifest schema, release key management, expiry, and anti-rollback policy.
3. Review the new TAR.GZ parser against the exact published runtime archive, especially symlink ordering/targets and required executable files.
4. Wire verified staging, transactional installation, metadata activation, and previous-runtime rollback into one installer state machine; keep UI controls disabled until that path is verified.
5. Obtain explicit approval for one debug build/unit-test job, then verify on the target Android 13 arm64 device before enabling registration or Start/Stop.
