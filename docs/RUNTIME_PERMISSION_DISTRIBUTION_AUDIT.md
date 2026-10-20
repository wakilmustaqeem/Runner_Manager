# Runtime, Permissions and Distribution Compatibility Notes

Audit type: read-only source/documentation review. No build, runner registration, workflow execution, or account/settings change.

## Upstream source revisions inspected

- `runtime/README.md` — `9b093bbf9e13004dae039899fb14b014a286acf4`
- `runtime/build-proot.sh` — `ad3a1bd062aa85e0b9986ff6f200942de7f819e3`
- `app/src/main/java/io/github/m96chan/droidrunner/runtime/RuntimeInstaller.kt` — `1281745c32131481f11bac861d2d1e461bd3ca70`
- `app/src/main/java/io/github/m96chan/droidrunner/runtime/RuntimeDownload.kt` — `0eb8847c40e432e27e2f0858f93717dbd329ca5b`
- `app/src/main/java/io/github/m96chan/droidrunner/runner/RunnerCommand.kt` — `00a080d5308285b901b8d4eba858a7be3f0ca496`
- `app/src/main/java/io/github/m96chan/droidrunner/runner/AdmissionPolicy.kt` — `163f96254f6cf9b59333138429872df0203284f2`
- `app/src/main/java/io/github/m96chan/droidrunner/runner/BootReceiver.kt` — `c3ee549f5f75cec269d1e009db217c533526db1b`
- `app/src/main/java/io/github/m96chan/droidrunner/runner/WatchdogJobService.kt` — `8ab6caeaa53698d9407636ead346f09d9906acd3`

## Runtime compatibility findings

1. DroidRunner separates Android-native PRoot/ELF loader binaries (packaged for `arm64-v8a`) from a downloaded Ubuntu ARM64 root filesystem containing the official Linux ARM64 GitHub Actions runner. This is a substantial runtime subsystem, not a few Kotlin registration calls.
2. The upstream README says runtime bundle production requires an ARM64 Linux build host and root/chroot capability. It documents a signed manifest, SHA-256 archive verification, and a separate signature check before installing the bundle. FM should not download or execute a runtime until the signing trust roots, artifact source, digest validation, architecture and rollback/recovery behavior have been reviewed.
3. The upstream build script documents PRoot under GPL-2.0 and talloc under LGPL-3.0, with source/compliance notes in the runtime README. Reusing binaries, source, or build recipes in a distributed FM APK requires a deliberate license and corresponding-source compliance review; this audit does not grant that approval.
4. `RunnerCommand.kt` configures the official runner with `config.sh`, repository URL, a short-lived token, and optional `--ephemeral`; removal uses the runtime's `config.sh remove`. FM's current source does not provide this runtime bridge.
5. Boot recovery and watchdog are separate Android components. The upstream boot receiver checks that a runtime is installed and configured before starting the foreground service. Watchdog/restart code does not eliminate Android force-stop, battery-management, credential-lock or OEM-specific limits; FM must describe reconnect as best-effort, not guaranteed.
6. Admission policy is a runtime boundary: upstream considers mains power, battery, free storage and heat. FM's current policy evaluator alone is not evidence that jobs are actually blocked at process/job admission.

## GitHub permission findings (official docs checked 2026-10-20)

- The repository registration-token endpoint requires repository Administration: write for fine-grained tokens; GitHub's classic OAuth/PAT guidance calls for `repo` on private repositories.
- The organization registration-token endpoint requires organization Self-hosted runners: write for fine-grained tokens; classic tokens need `admin:org` and private-repository access also requires `repo`.
- GitHub documents registration tokens as expiring after one hour. This is the temporary runner-registration token, not proof that the user's sign-in token expires after one hour.
- Reading existing runner details uses read permissions, distinct from registration/removal. Do not grant organization-wide access when the intended target is one repository.
- These requirements are from GitHub's official documentation:
  - https://docs.github.com/en/rest/actions/self-hosted-runners
  - https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens

## FM-specific gate

Before implementation, make an explicit design decision on:
- repository-scoped first, with the target account and full repository name visibly confirmed;
- minimum permission set for that target and credential type;
- whether persistent or ephemeral registration is the default;
- encrypted credential storage and explicit Disconnect/revocation behavior;
- signed runtime manifest and SHA-256 verification;
- Android foreground-service, notification, boot and battery settings, each justified separately;
- a tested, fail-closed admission gate for battery/charging/storage/temperature;
- recovery, removal failure, lost network and app/process death state transitions.

## Status

Documentation only. No changes to `main`, Android source, manifest, dependencies, runtime binaries, workflows, GitHub settings, credentials or production. No build or Actions run was started. FM's runner controls must remain disabled until a reviewed implementation and separately approved test exist.
