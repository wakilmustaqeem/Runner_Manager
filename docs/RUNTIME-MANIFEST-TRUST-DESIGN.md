# FM Runtime Manifest Trust and Integrity Contract

Branch: `feat/runner-manager-active-runner-implementation-v1`

Status: design contract for implementation and review; not evidence of a signed release or a successful device installation.

## 1. Existing verification model

The current implementation uses a detached Base64 ECDSA P-256 / SHA-256 signature over the exact UTF-8 manifest bytes received from HTTPS. The signature is verified before JSON parsing. The public key is configured separately as Base64 X.509 SubjectPublicKeyInfo DER. This is an exact-byte signature model, not a canonical-JSON signature model.

Do not mix the two models. If the format later changes to a signature field inside canonical JSON, specify canonicalization precisely and reject duplicate JSON keys before verifying the canonical payload.

## 2. Target manifest schema v2

The signed payload requires:

- `schemaVersion`: integer exactly equal to the supported schema version.
- `version`: positive, monotonically increasing integer release sequence.
- `platform`: exactly `android-arm64` for this target.
- `archiveUrl`: absolute HTTPS URL without embedded credentials.
- `archiveBytes`: positive bounded integer.
- `archiveSha256`: exactly 64 hexadecimal characters.
- `signedAtEpochSeconds`: non-negative integer Unix timestamp in seconds.
- `expiresAtEpochSeconds`: integer Unix timestamp in seconds; expired manifests fail closed.
- `keyId`: stable identifier matching a configured, non-revoked trust entry.

The detached signature remains outside the JSON payload and covers every byte of the payload. The signature must not be treated as a field that can be omitted from an embedded-signature canonicalization algorithm. Timestamp values are epoch seconds, not ISO-8601 strings or milliseconds.

## 3. Verification order

1. Validate configuration and enforce HTTPS before network access.
2. Fetch manifest bytes under a strict byte limit.
3. Verify the detached signature over the exact received bytes using the configured pinned trust key.
4. Decode UTF-8 strictly; reject malformed input.
5. Parse JSON and reject duplicate keys, unsupported schema, missing required fields, and unexpected types.
6. Match `keyId` against a versioned allow-list; reject unknown or revoked IDs. Bind the configured public key to that ID—an ID supplied by the manifest is not proof of trust.
7. Validate platform, release version, timestamps/expiry policy, archive URL, size, and SHA-256 syntax.
8. Enforce anti-rollback against the active installed version and any separately configured minimum release floor.
9. Download with redirects disabled. Enforce byte limits and calculate SHA-256 incrementally from the exact bytes written to the staging file.
10. Require exact byte count and digest match before finalizing the staged file. Re-verify the finalized file before extraction/publication to cover local file changes.
11. Extract into a fresh private temporary directory. On any error, remove incomplete staging and do not publish the active pointer.
12. Publish the installed-runtime pointer only after extraction validation succeeds.

Any failed check or exception must leave the currently active runtime pointer unchanged.

## 4. Key management

- Never commit a production private key to the repository or Android application.
- The Android app may contain public trust material only.
- Store `keyId -> public key -> status` in a versioned, reviewable trust configuration.
- Unknown and revoked keys fail closed.
- Rotation must define an overlap period and an explicit revocation/recovery process; never silently trust a new key merely because it appears in a manifest.
- Test keys and test private material must be isolated from production keys and excluded from release artifacts.
- Reproducible test signing should use a local test-only script and deterministic manifest bytes. A successful local signature test does not establish production trust.

## 5. Runtime pointer and transaction files

The installation transaction uses an active pointer plus `.pending`, `.previous`, and `.commit` metadata. Pointer schema v2 must be parsed strictly and validated before it is trusted. Transaction IDs must be non-empty, bounded, and compared exactly. Paths must resolve only to permitted app-private runtime locations; reject traversal, unexpected absolute paths, malformed values, and unsupported schema versions.

A valid active pointer must reference a fully extracted and validated runtime whose manifest identity, version, platform, and integrity metadata agree with the pointer. Pointer validity alone does not make an incomplete runtime valid. The configured minimum release floor applies to every candidate restored or activated during recovery.

Transaction publication order:
1. Stage and verify the archive; extract into a fresh private directory.
2. Validate the extracted runtime completely.
3. Write and durably flush the pending pointer.
4. Write and durably flush commit metadata for the same transaction ID.
5. Atomically publish the active pointer.
6. Retain the previous valid pointer until the transaction is confirmed complete; cleanup is a separate recoverable step.

Atomic rename and directory durability are platform/filesystem dependent. Where directory fsync is unavailable or not reliable, document that limitation and rely on startup recovery; do not claim stronger crash durability than the platform provides.

## 6. Recovery rules

Recovery must validate each candidate pointer, referenced runtime, transaction ID, and release floor before using it. A signature check alone is not sufficient to validate pending state. Never infer commit status from the mere existence of a file.

| Row | `.commit` / transaction state | `.pending` | `active` | `.previous` | Required recovery |
|---|---|---|---|---|---|
| 1 | Missing or invalid | Any | Valid and floor-compliant | Any | Keep active; remove untrusted transaction residue only after safely identifying it. |
| 2 | Valid; commit ID differs from active ID | Valid and fully validated for the same transaction | Valid old active | Valid or absent | Roll forward only after re-validating pending runtime, manifest/signature, transaction binding, and floor; otherwise use the safe rollback/failure rules. |
| 3 | Valid; commit ID matches pending ID | Valid and fully validated | Missing/invalid | Valid or absent | Activate pending only after all validation gates pass; otherwise restore a valid floor-compliant previous pointer or fail safely. |
| 4 | Valid; commit ID matches pending ID | Valid and fully validated | Valid old active with a different transaction ID | Valid or absent | Roll forward from pending only after complete re-validation of pending and its transaction binding. |
| 5 | Valid; commit ID differs from active ID | Invalid/missing | Valid old active | Any | Do not activate pending; preserve valid active and clean only safe-to-delete transaction residue. |
| 6 | Valid; `t == active.t` | Present (valid or invalid) | Valid | Any | Transaction already committed. Do NOT re-activate. Cleanup only: delete `.pending`, `.previous`, `.commit`. Active unchanged. |
| 7 | Valid; `t == active.t` | Any | Invalid/missing | Valid and floor-compliant | Do NOT roll forward from `.pending`. Restore `.previous` → `active`. Delete `.pending`, `.commit`. If `.previous` is invalid or the floor fails, safe failure (row 11). |
| 8 | Valid | Any | Invalid/missing | Invalid/missing | Safe failure; do not invent an active runtime. |
| 9 | Malformed or inconsistent transaction metadata | Any | Valid and floor-compliant | Any | Keep active; reject malformed metadata and perform only safe cleanup. |
| 10 | Valid but transaction IDs disagree across required metadata | Any | Valid and floor-compliant | Any | Keep active; do not activate a candidate with inconsistent transaction binding. |
| 11 | Any state where no candidate is fully valid and floor-compliant | Any | Invalid/missing | Invalid/missing or floor failure | Safe failure: leave runtime inactive, emit an audit event, and require an explicit recovery/install action. |

When `commit.transactionId == active.transactionId`, the transaction is considered committed. Recovery MUST NOT re-activate. Only rollback to `.previous` or safe failure is permitted. `.pending` is treated as uncommitted residue and is deleted without validation.

Row 6 is the valid-active terminal case: cleanup must not alter `active`. Row 7 is the invalid-active case: rollback-only; pending is never a roll-forward candidate. Row 4's roll-forward rule applies only when the active transaction ID differs from the committed transaction ID.

## 7. Invariants

- **Fail closed:** any ambiguity or validation error prevents activation.
- **Committed transaction:** `t == active.t` implies committed; roll forward is disabled.
- **No reactivation:** a transaction whose ID already matches active must not be activated a second time.
- **Pending is not trusted by presence:** pending must be fully revalidated before any roll-forward path.
- **Active preservation:** if active is valid and floor-compliant, cleanup must not change it.
- **Rollback floor:** previous may be restored only after pointer/runtime validation and minimum release-floor checks.
- **No unsafe fallback:** if no candidate is fully valid and floor-compliant, fail safely rather than selecting the least-invalid candidate.
- **Auditability:** recovery decisions and failures produce bounded audit events without secrets or tokens.
- **Idempotency:** repeating recovery after a crash converges to the same valid state and does not reactivate an already committed transaction.
- **No authority escalation:** parsing or learning metadata does not authorize account changes, runner registration, or job execution.

## 8. Strict JSON parser contract

Use a bounded recursive-descent parser for manifest, pointer, and trust configuration data. Reject duplicate object keys, invalid escapes, malformed Unicode/surrogate pairs, leading-zero numbers, incomplete fractions/exponents, trailing data, comments, non-JSON literals, and invalid UTF-8. Enforce explicit limits for input bytes, nesting depth, object member count, array length, string length, and numeric token length. Do not coerce values across types.

The JSON number representation must preserve the original token or parse to a checked numeric representation without precision loss. Integer fields must reject decimal/exponent forms unless the schema explicitly permits them, and must reject overflow before conversion. No permissive platform parser may be used as a second parser with different acceptance rules.

## 9. Download and extraction safety

- Disable redirects unless a separately reviewed allow-list policy is added.
- Require HTTPS and validate the final requested URL policy before reading bytes.
- Enforce both declared and actual byte limits; abort on overrun and reject short downloads.
- Hash incrementally over the bytes written to staging; compare exact byte count and digest.
- Re-verify the completed staged file before extraction.
- Reject archive path traversal, absolute paths, symlinks escaping the destination, duplicate/conflicting entries, excessive expanded size, excessive entry count, and suspicious compression ratios.
- Extract only to a fresh private directory; publish nothing until all validation completes.
- Clean incomplete staging on failure without touching the active runtime.

## 10. Acceptance and failure-injection tests

Required source-level tests include valid/invalid signatures; modified manifest bytes; unknown/revoked key IDs; malformed UTF-8/JSON; duplicate keys; unsupported schema/platform; expired timestamps; oversized and short downloads; digest mismatch; anti-rollback/floor rejection; unsafe archive paths; interrupted staging; pointer write/rename failures; and idempotent recovery after interruption at each transaction boundary.

Explicit recovery regression test: **row 7 + invalid pending → no re-activation**. Also test row 6 with invalid/present pending → cleanup only and active pointer unchanged. Test that `t == active.t` disables roll-forward even if pending appears otherwise valid.

Acceptance requires static review of the parser and key configuration, followed by one explicitly approved bounded build/test run and target Android 13 arm64 device validation using an FM-signed test artifact. This document does not authorize running those checks.

## 11. Current implementation status and limitations

The existing implementation does not yet enforce this full target schema, versioned key-ID revocation list, strict parser contract, or complete recovery state machine. Archive hashing must be accumulated during download and the finalized staged file must be re-verified. This document is a design contract, not a claim that these changes are already implemented or tested.
