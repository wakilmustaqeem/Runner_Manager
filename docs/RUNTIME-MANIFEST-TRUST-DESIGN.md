# FM Runtime Manifest Trust and Integrity Contract

Branch: `feat/runner-manager-active-runner-implementation-v1`

Status: design contract for implementation and review; not evidence of a signed release or a successful device installation.

## Existing verification model

The current implementation uses a detached Base64 ECDSA P-256 / SHA-256 signature over the exact UTF-8 manifest bytes received from HTTPS. The signature is verified before JSON parsing. The public key is configured separately as Base64 X.509 SubjectPublicKeyInfo DER. This is an exact-byte signature model, not a canonical-JSON signature model.

Do not mix the two models. If the format later changes to a signature field inside canonical JSON, specify canonicalization precisely and reject duplicate JSON keys before verifying the canonical payload.

## Target manifest contract

The next schema revision should require all of these fields in the signed payload:

- `schemaVersion`: integer, exactly the supported schema version.
- `version`: positive monotonically increasing integer release sequence.
- `platform`: exactly `android-arm64` for this release target.
- `archiveUrl`: absolute HTTPS URL, without embedded credentials.
- `archiveBytes`: positive bounded integer.
- `archiveSha256`: exactly 64 hexadecimal characters.
- `signedAt`: ISO-8601 UTC timestamp.
- `expiresAtEpochSeconds`: expiry timestamp; expired manifests fail closed.
- `keyId`: stable identifier that must match a configured, non-revoked trust entry.

The detached signature remains outside the JSON payload and covers every byte of the payload. The signature must not be treated as a field that can be omitted from an embedded-signature canonicalization algorithm.

## Verification order

1. Validate configuration and enforce HTTPS before network access.
2. Fetch manifest bytes under a strict byte limit.
3. Verify detached signature over the exact received bytes with the configured pinned trust key.
4. Decode UTF-8 strictly; reject malformed input.
5. Parse JSON and reject duplicate keys, unsupported schema, missing required fields, and unexpected types.
6. Match `keyId` against a versioned allow-list and reject revoked/unknown IDs. The configured public key must be bound to that ID; a caller-supplied ID alone is not proof of trust.
7. Validate platform, release version, signed-at/expiry policy, archive URL, size, and SHA-256 syntax.
8. Enforce anti-rollback against the active installed version and any separately configured minimum release floor.
9. Download with redirects disabled. Enforce byte limits and calculate SHA-256 incrementally from the exact bytes written to the staging file.
10. Require exact byte count and digest match before finalizing the staged file. Re-verify the finalized file before extraction/publication to cover local file changes.
11. Extract into a fresh private temporary directory; on any error, remove incomplete staging and do not publish the active pointer.
12. Publish the installed-runtime pointer only after extraction validation succeeds.

Any failed check or exception must leave the currently active runtime pointer unchanged.

## Key management

- Never commit a production private key to the repository or Android application.
- The Android app may contain public trust material only.
- Store `keyId -> public key -> status` in a versioned, reviewable trust configuration.
- Unknown and revoked keys fail closed.
- Rotation must define an overlap period and an explicit revocation/recovery process; do not silently trust any new key merely because it appears in a manifest.
- Test keys and test private material must be isolated from production keys and excluded from release artifacts.
- Reproducible test signing should use a local test-only script and deterministic manifest bytes. A successful local signature test does not establish production trust.

## Acceptance gates

- Unit tests for valid/invalid signatures, changed bytes, unknown/revoked key IDs, malformed UTF-8/JSON, duplicate keys, unsupported platform, expiry, oversized/short downloads, hash mismatch, rollback, and failure cleanup.
- Static review of the exact manifest parser and key configuration.
- One explicitly approved bounded build/test run.
- Target Android 13 arm64 device validation using an FM-signed test artifact before production keys or live runner registration are enabled.

## Current limitations

The existing implementation does not yet enforce this full target schema or a key-ID revocation list. Archive hashing currently occurs after the download finishes rather than being accumulated in the download loop. This document is a design contract, not a claim that those changes are already implemented or tested.
