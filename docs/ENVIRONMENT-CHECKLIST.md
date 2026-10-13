# Environment readiness checklist

This checklist is for **manual, read-only inspection only**. FM Runner Manager does not run these commands, install tools, request shell access, or register a runner.

## Before checking

- Confirm which GitHub account and repository you intend to use.
- Keep each account's token, runner registration, work directory, and configuration separate.
- Do not paste tokens, passwords, registration tokens, or full environment dumps into chat, Issues, commits, or logs.
- Do not run `apt install`, `pkg install`, `dpkg`, root commands, or scripts just to complete this checklist.
- If you are not already using a terminal environment, skip terminal checks. The native Android dashboard can report device details but cannot reliably inspect Termux/proot packages by itself.

## Record the environment first

Record only the output needed for troubleshooting:

| Item | Safe check | Notes |
|---|---|---|
| Android | Settings → About phone | Record Android version and device model. |
| CPU architecture | `uname -m` in an existing terminal | Common values include `aarch64` or `arm64`; this is not proof that a runner binary is supported. |
| Java runtime | `java -version` | “Command not found” means not detected in this shell. |
| Java compiler | `javac -version` | A JRE may exist without a JDK/compiler. |
| Git | `git --version` | Read-only version check. |
| Gradle | `gradle --version` | Do not install it solely because it is missing. |
| Android SDK | Check existing SDK configuration and installed platform directories | Do not download SDK components yet. |
| Disk space | Android Settings → Storage, or `df -h` in an existing terminal | Keep free space for build output and caches. |

These checks are optional and run only if the commands already exist in a terminal you intentionally opened. They do not establish that GitHub Actions self-hosted runners are officially supported on the current Android/proot setup.

## Environment types are not interchangeable

- **Native Android app:** the current app runs as an ordinary Android app. It does not execute shell commands or inspect another app's private files.
- **Termux:** a separate terminal environment. Package names, paths, permissions, and available binaries differ from standard Linux.
- **proot/Linux distribution:** a userspace Linux environment with its own filesystem and package database. It does not imply root access or full Linux kernel compatibility.
- **Self-hosted GitHub Actions runner:** requires a compatible runner binary/runtime and a valid, separately authorized repository or organization registration. Android ARM64 alone is not enough to confirm compatibility.

## Readiness result format

Use only these states:

- **Detected:** a read-only check confirmed the tool and version.
- **Not detected:** the check says missing or the command is unavailable.
- **Not checked:** no check was performed.
- **Incompatible / unverified:** the tool exists, but compatibility with the intended runner/build workflow has not been established.

## Approval gate

Before any installation or runner registration, present the exact source, target environment, approximate download size if known, required permissions, account/repository target, possible cost, and rollback plan. Wait for explicit owner approval. Never store secrets in plain-text preferences or audit logs.
