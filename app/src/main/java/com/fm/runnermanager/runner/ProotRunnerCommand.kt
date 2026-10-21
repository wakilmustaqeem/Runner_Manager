package com.fm.runnermanager.runner

import java.io.File

/**
 * Builds, but never starts, the DroidRunner-compatible PRoot command for the
 * official GitHub Actions runner. Native libraries and the signed runtime bundle
 * must be installed and verified separately before a caller may execute it.
 * Never log command arguments: registration/removal tokens are process arguments.
 */
internal object ProotRunnerCommand {
    data class Paths(
        val nativeLibraryDir: File,
        val cacheDir: File,
        val runtimeDir: File
    )

    fun runtimeProblems(paths: Paths): List<String> {
        val required = listOf(
            File(paths.nativeLibraryDir, "libproot.so") to "PRoot native library",
            File(paths.nativeLibraryDir, "libproot-loader.so") to "PRoot loader",
            File(paths.nativeLibraryDir, "libproot-loader32.so") to "PRoot 32-bit loader",
            File(paths.runtimeDir, "rootfs/usr/bin/env") to "runtime root filesystem",
            File(paths.runtimeDir, "home/runner/config.sh") to "runner configuration script",
            File(paths.runtimeDir, "home/runner/run.sh") to "runner listener script"
        )
        return required.filterNot { (file, _) -> file.isFile }
            .map { (_, label) -> "$label is missing" }
    }

    fun configure(
        paths: Paths,
        repositoryUrl: String,
        runnerName: String,
        labels: Set<String>,
        token: String,
        ephemeral: Boolean = false
    ): ProcessBuilder {
        require(repositoryUrl.matches(Regex("https://github\\.com/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?")) &&
            !repositoryUrl.contains("/../")) { "Invalid runner target URL" }
        require(runnerName.isNotBlank() && runnerName.none { it == '\n' || it == '\r' }) {
            "Invalid runner name"
        }
        require(labels.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Invalid runner labels" }
        require(token.isNotBlank() && token.none { it == '\n' || it == '\r' }) {
            "Registration token is required"
        }
        val args = mutableListOf(
            "/home/runner/config.sh", "--unattended",
            "--url", repositoryUrl, "--token", token,
            "--name", runnerName, "--labels", labels.sorted().joinToString(","),
            "--work", "_work"
        )
        if (ephemeral) args += "--ephemeral"
        return proot(paths, args)
    }

    fun remove(paths: Paths, token: String): ProcessBuilder {
        require(token.isNotBlank() && token.none { it == '\n' || it == '\r' }) {
            "Removal token is required"
        }
        return proot(paths, listOf("/home/runner/config.sh", "remove", "--token", token))
    }

    fun run(paths: Paths): ProcessBuilder = proot(paths, listOf("/home/runner/run.sh"))

    private fun proot(paths: Paths, command: List<String>): ProcessBuilder {
        val problems = runtimeProblems(paths)
        check(problems.isEmpty()) { "Runner runtime is not ready: ${problems.joinToString("; ")}" }

        val rootfs = File(paths.runtimeDir, "rootfs").absolutePath
        val home = File(paths.runtimeDir, "home").absolutePath
        val tmp = File(paths.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        val native = paths.nativeLibraryDir.absolutePath
        val builder = ProcessBuilder(
            listOf(
                File(paths.nativeLibraryDir, "libproot.so").absolutePath,
                "--kill-on-exit", "-0", "-r", rootfs,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "$home:/home", "-w", "/home/runner",
                "/usr/bin/env",
                "HOME=/home/runner", "LANG=C.UTF-8", "TMPDIR=/tmp",
                "RUNNER_ALLOW_RUNASROOT=1", "DOTNET_GCHeapHardLimit=0x30000000",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            ) + command
        )
        builder.environment()["PROOT_LOADER"] = "$native/libproot-loader.so"
        builder.environment()["PROOT_LOADER_32"] = "$native/libproot-loader32.so"
        builder.environment()["PROOT_TMP_DIR"] = tmp
        return builder
    }
}
