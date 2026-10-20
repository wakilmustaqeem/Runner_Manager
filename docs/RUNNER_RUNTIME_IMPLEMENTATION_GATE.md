# FM Runner runtime integration — current implementation gate

Branch: `feat/runner-registration-runtime-v1`

## Source comparison

DroidRunner's runtime architecture has two independently required pieces:

1. PRoot and its ELF loaders compiled for Android and packaged under `jniLibs/arm64-v8a`.
2. A data-only ARM64 Linux rootfs containing the official GitHub Actions runner, downloaded as a tarball.

A real installation also verifies the exact manifest bytes with a trusted ECDSA P-256 public key, verifies the runtime archive SHA-256, extracts to staging, checks required runner files, and activates with recovery. Runner registration then invokes `/home/runner/config.sh --unattended --replace --url ... --token ...`; the service owns `/home/runner/run.sh` and must observe its process and GitHub status.

## FM code added so far

- Repository-scoped GitHub registration-token request in `GitHubDeviceFlowClient.kt`.
- `ProotRunnerCommand` builds configure/remove/listener commands and refuses to construct a process if the PRoot libraries, rootfs entrypoint, or runner scripts are missing.
- Unit tests cover the command arguments and fail-closed behavior.
- `RuntimeManifestSignature` verifies detached ECDSA signatures over the exact manifest bytes and explicitly reports an empty trust store as unverifiable.
- Gradle accepts `runtimeSigningKeys` / `FM_RUNTIME_SIGNING_KEYS` and `runtimeManifestUrl` / `FM_RUNTIME_MANIFEST_URL`. Defaults are pinned to the inspected public DroidRunner `runtime-0.3.0` manifest and its public ECDSA verification key. A custom URL can be supplied only with a matching trusted signing key; empty key configuration must remain a hard block.

## Still blocking a real runner

- No verified PRoot native binaries currently exist in FM's `jniLibs/arm64-v8a`.
- No runtime manifest downloader, SHA-256 archive verifier, safe tar extractor, staged activation/rollback, or runtime installer is wired into FM.
- The Android manifest currently declares only INTERNET and the launcher activity; no foreground runner service or notification permissions/service declaration exist.
- Registration token is not yet connected to the process builder or UI; the process builder is deliberately not called from the dashboard.
- No tests/build have been run for these commits, and no live GitHub Runner status has been verified.

## Safety rule

Do not report the runner as registered/online until the process has configured successfully and the GitHub repository's runner endpoint reports the matching runner. Do not download or execute an unsigned/unverified rootfs. Keep all changes on this feature branch until reviewed.
