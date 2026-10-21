# Runner_Manager — Project History

Repository: wakilmustaqeem/Runner_Manager
Branch (active): feat/runner-manager-active-runner-implementation-v1
Main branch: NOT modified in this phase.
Last updated: 2026-10-21

## Purpose

Runtime manager for GitHub Actions runner on Android via PRoot. Current phase
focuses on: manifest signature/trust, strict JSON parsing, transactional
pointer write, and deterministic startup recovery. PRoot launch adapter and
live registration are NOT enabled in this phase.

## Key decisions

- Signature model: detached signature over EXACT manifest bytes.
  No canonical JSON, no whitespace normalization, no key reordering.
- Manifest schema v2 is the only accepted schema. v1 rejected in production.
- Timestamp field renamed to `signedAtEpochSeconds`; all timestamps are
  epoch seconds, UTC.
- `platform`, `keyId`, `signedAtEpochSeconds` are mandatory.
- keyId read happens in two passes (untrusted pre-signature key selection
  then authenticated strict parse). Signature verify uses ONLY the key
  selected by candidateKeyId.
- Trust anchor is build-time pinned public key. Never fetched over network.
  Production private key is NOT stored in this repository.
- Streaming SHA-256 during download; size abort fires during scan, not after.
- Pointer schema v2 adds `transactionId`. Commit marker (schema v1) binds
  txnId AND version AND sha256 (not txnId alone).
- Recovery: deterministic 11-row matrix. Row 7 (commit binds active but
  active invalid) is rollback-only — never re-activates.
- ATOMIC_MOVE unsupported → fail closed; no non-atomic fallback.
- Directory fsync on Android is NOT portable; commit marker provides
  application-level recovery, not absolute power-loss durability.
- Idempotency: running recovery twice produces zero additional mutations.

## Completed work

### Commit `b5af9da` — Design document
File: `docs/RUNTIME-MANIFEST-TRUST-DESIGN.md`
- v2 manifest schema, 11-row recovery matrix, commit marker protocol,
  pointer schema v2, StrictJson parser spec, ATOMIC_MOVE fail-closed,
  directory fsync limitation, version floor at both points.
- Row 6/7 conflict corrected: when `commit.transactionId == active.transactionId`,
  transaction is committed; recovery MUST NOT re-activate.

### Commit `92dc26374cc5beb6f9cab0deda781d663c8f2b23` — StrictJson (S1)
Files (3):
- `app/src/main/java/com/fm/runnermanager/runtime/JsonValue.kt`
- `app/src/main/java/com/fm/runnermanager/runtime/StrictJson.kt`
- `app/src/test/java/com/fm/runnermanager/runtime/StrictJsonTest.kt`

Notes: two commits total (initial + interpolation fix). Tests NOT run.
Build/Actions NOT run.

### Commit `245c46e22aaf7a614ed4dedd69468f3917a5d670` — Commit A: Runtime pointer transaction
Files (4):
- `app/src/main/java/com/fm/runnermanager/runtime/RuntimePointer.kt`
- `app/src/main/java/com/fm/runnermanager/runtime/CommitMarker.kt`
- `app/src/main/java/com/fm/runnermanager/runtime/RuntimePointerTransaction.kt`
- `app/src/test/java/com/fm/runnermanager/runtime/RuntimePointerTransactionTest.kt`

Source committed on the feature branch. Test source added but tests NOT run.
Build/Actions NOT run. `InstalledRuntimeStore.kt` was not changed.

## Pending

### Commit B — Installer adapter (NOT STARTED)
Will modify `InstalledRuntimeStore.installAndActivate(...)` to call
`RuntimePointerTransaction.writeActive(...)` after extraction+validation.
Startup recovery will call `RuntimePointerTransaction.recover()`.

## Bugs found and fixed during design

| Bug | Severity | Fix |
|---|---|---|
| `%02x`.format(Byte) produces wrong hex for negative bytes | P0 | `it.toInt() and 0xFF` |
| `StringBuilder.append(cp: Int)` in `\uXXXX` decoding | P0 | `append(cp.toChar())` |
| Depth limit off-by-one (65th container accepted) | P1 | check BEFORE consuming container |
| Commit binding only txnId | P0 | txnId AND version AND sha256 |
| `formatVersion` Long→Int overflow | P1 | Int-range check before `toInt()` |
| Symlink escape via `runtimeDir` | P1 | symlink + canonical containment |
| `serialize()` without prior `validate()` | P1 | `validate()` first |
| Corrupt `.pending` not cleaned if unparseable | P1 | delete on presence+parse-fail |
| Duplicate check after member-count limit | P1 | duplicate first |
| String limits only checked at close quote | P1 | checked during scan (`bump()`) |

## Verification status

| Item | Status |
|---|---|
| Build | **NOT RUN** |
| Unit tests | **NOT RUN** |
| GitHub Actions | **NOT RUN** |
| Live device test | **NOT RUN** |
| Production key | NOT present in repo |
| PRoot launch | NOT enabled |

## Next steps

1. Commit B (adapter) — modify `InstalledRuntimeStore.kt`, add startup
   recovery call site.
2. User-granted build/test run (separate approval).
3. Android device verification.
4. Signed runtime bootstrap (production keys, not in repo).

## Limitations of this record

- This file is a structured project-history summary, not a verbatim transcript
  of all ChatGPT/DeepSeek conversations.
- Verbatim conversation requires explicit export of the original transcript;
  only source text actually available should be archived.
- No build/test evidence exists. Commit A contains source and test code only.
- Some prior conversation turns are truncated in the source record;
  the summary captures only what was actually discussed.
