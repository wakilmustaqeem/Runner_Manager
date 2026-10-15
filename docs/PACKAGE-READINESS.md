# Package readiness policy

The app must inspect before it proposes installing anything. Android, Termux, and proot/Linux are different environments; a package being installed in one does not prove it is available in another.

## Initial tool matrix

| Tool | Purpose | Current status |
|---|---|---|
| Git | Source checkout and version control | Not detected by this scaffold |
| Java 17 JDK | Android Gradle build toolchain | Not detected by this scaffold |
| Gradle 8.9+ | Build system compatible with the selected Android Gradle Plugin | Not detected by this scaffold |
| Android SDK Platform 35 | Compile target | Not detected by this scaffold |
| PHP 8.3 | Optional PHP lint jobs for a separately configured runner | Not detected by this scaffold |
| GitHub Actions runner | Executes a runner job in a supported environment | Not configured by this scaffold |

These are requirements to verify, not claims that any package is installed.

## Rules before installation

1. Identify the exact environment and CPU architecture.
2. Check whether the tool is already present and report its version.
3. Explain the package source, approximate download size if known, and required permissions.
4. Ask for approval before any install or environment mutation.
5. Do not use `sudo`, root, `apt`, or `dpkg` automatically.
6. If installation fails, preserve the error and stop; do not loop or repeatedly download.
7. Never place credentials, access tokens, or runner registration tokens in logs.
