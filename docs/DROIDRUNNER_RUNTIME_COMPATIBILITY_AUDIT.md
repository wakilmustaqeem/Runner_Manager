# Runtime, Architecture and Licensing Compatibility Audit

Audit type: read-only source inspection. No app implementation or build was performed.

## Inspected upstream source revisions

Repository: https://github.com/m96-chan/DroidRunner

- `RuntimeDownload.kt` — `0eb8847c40e432e27e2f0858f93717dbd329ca5b`
- `RuntimeInstaller.kt` — `1281745c32131481f11bac861d2d1e461bd3ca70`
- `ManifestSignature.kt` — `efcee823c7d4a68714ed28b04c7d67556cdd6c43`
- `DeviceCliInstaller.kt` — `0284d93a67c288e3be46a0edf9c85d7823828589`
- `RunnerCommand.kt` — `00a080d5308285b901b8d4eba858a7be3f0ca496`
- `BootReceiver.kt` — `c3ee549f5f75cec269d1e009db217c533526db1b`
- `WatchdogJobService.kt` — `8ab6caeaa53698d9407636ead346f09d9906acd3`
- `runtime/README.md` — `9b093bbf9e13004dae039899fb14b014a286acf4`
- `app/build.gradle.kts` — `263bb1345bf5bded41ad3d45356fb0df51ca3837`
- `LICENSE` — `1f963da0d1ca40ea60730c03befcbbc6771740c3`

## Findings

### Runtime architecture is a substantial subsystem

DroidRunner does not simply execute a downloaded Linux binary from app storage. Its README explains that PRoot and its loaders are packaged in APK native libraries because Android 10+ restricts executing binaries from app data. A separate first-run runtime bundle provides an ARM64 Linux root filesystem and the official Actions runner. The runner CLI is installed into the guest rootfs from an APK asset.

This means FM's current Kotlin adapters/UI cannot become an active runner by adding a single API call. A runtime, process invocation boundary, guest filesystem, updates, lifecycle supervision, and failure recovery all need their own design and tests.

### Download integrity and publisher authenticity are separate checks

The inspected runtime installer requires HTTPS for manifest and archive URLs. The downloader checks the archive's SHA-256 against the manifest and can resume partial downloads. The manifest verifier uses ECDSA/SHA-256 against public keys compiled into the APK.

Important configuration caveat: upstream Gradle documents that an empty signing-key configuration means signatures cannot be checked. The runtime README says signing depends on `RUNTIME_SIGNING_KEY`. Any FM design must fail closed if its trusted signing key is absent, the signature is missing/invalid, or the digest does not match; HTTPS and a hash alone do not establish publisher authenticity if the manifest itself is untrusted.

### Activation is staged and recovery-aware

The installer downloads to a temporary archive, extracts into a staging directory, validates expected runtime files, marks the version, and activates the staged runtime while retaining/recovering the previous directory. The code also preserves registration details during runtime replacement. FM should not overwrite a working runtime in place or delete registration metadata during an update.

### Architecture constraints

Upstream Gradle currently filters the native build to `arm64-v8a` and uses Java/Kotlin 17. The user's device is ARM64/Android 13, so the ABI appears directionally compatible, but this is not proof that an upstream APK/runtime can be safely reused on that device. A compatible artifact, runtime version, NDK/native libraries, disk budget and thermal/battery behavior still require a controlled diagnostic. No such test was run.

### Reconnect is best-effort, not a guarantee

The boot receiver starts the service only when autostart is enabled and the runtime is installed and configured. A scheduled watchdog can attempt recovery, but Android and vendor battery management can still restrict background execution. Persistent sign-in also remains subject to credential expiry, revocation, changed permissions and network availability.

FM should describe reconnect as best-effort recovery and expose a clear status/error, rather than promise an always-on runner or a login that never expires.

### Licensing needs a separate distribution review

The upstream repository's top-level license file is GNU GPL version 2. The runtime README separately discusses PRoot's source/corresponding-source obligations and the runtime bundle. This is not sufficient to determine every dependency's license or the exact obligations for FM's intended distribution. Before copying or shipping upstream code/binaries, review per-component licenses, notices, source availability and any distribution obligations. Do not assume copying source into a reference folder grants blanket permission to repackage the runtime.

## FM recommendation

Keep upstream reference code passive. Before any runtime implementation:
1. Inventory each component and its license/provenance.
2. Define trusted runtime-manifest keys and fail-closed verification.
3. Specify staging, rollback, disk-space checks and version pinning.
4. Define service/autostart/watchdog behavior with Android permission and user-consent checks.
5. Establish one-device, one-job limits plus battery, temperature and storage admission checks.
6. Design tests for corrupt downloads, missing signature keys, interrupted updates, revoked credentials, failed registration and disconnect cleanup.

No changes to `main`, workflows, account settings, runner registration or production were made. No build or GitHub Actions run was started.
