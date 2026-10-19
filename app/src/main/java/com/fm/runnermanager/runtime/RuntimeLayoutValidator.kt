/*
 * FM Runner Manager runtime-readiness gate.
 *
 * The layout checks follow the public DroidRunner runtime layout:
 * https://github.com/m96-chan/DroidRunner
 *
 * DroidRunner is GPL-2.0-only. This new FM file is an independent readiness
 * check, not a copy of upstream implementation code. Review upstream notices
 * and corresponding-source obligations before adapting or distributing any
 * upstream runtime code.
 *
 * IMPORTANT: a passing result verifies file layout only. It does not verify
 * bundle signatures, archive digests, GitHub registration, process liveness,
 * or that a runner is online. Never use it alone to enable Start or show Online.
 */
package com.fm.runnermanager.runtime

import java.io.File

data class RuntimeReadiness(
    val readyForServicePreflight: Boolean,
    val installedVersion: String?,
    val blockers: List<String>
)

/**
 * Fail-closed, read-only structural checks for an already-installed runtime.
 *
 * This class never downloads, extracts, registers, starts, stops, or deletes
 * anything. Callers must separately verify runtime provenance/integrity and
 * obtain live service/process state before enabling runner controls.
 */
object RuntimeLayoutValidator {
    fun inspect(runtimeDir: File, nativeLibraryDir: File?): RuntimeReadiness {
        val blockers = mutableListOf<String>()
        val marker = File(runtimeDir, ".installed")
        val rootfsEnv = File(runtimeDir, "rootfs/usr/bin/env")
        val runnerScript = File(runtimeDir, "home/runner/run.sh")
        val configScript = File(runtimeDir, "home/runner/config.sh")
        val prootLibrary = nativeLibraryDir?.let { File(it, "libproot.so") }

        if (!runtimeDir.isDirectory) blockers += "Runtime directory is missing"
        if (!marker.isFile) blockers += "Installed-version marker is missing"
        if (!rootfsEnv.isFile) blockers += "Runtime rootfs is incomplete"
        if (!runnerScript.isFile || !runnerScript.canExecute()) {
            blockers += "Official runner script is missing or not executable"
        }
        if (!configScript.isFile || !configScript.canExecute()) {
            blockers += "Runner configuration script is missing or not executable"
        }
        if (prootLibrary == null || !prootLibrary.isFile) {
            blockers += "Packaged ARM64 PRoot library is missing"
        }

        val version = marker.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

        if (marker.isFile && version == null) {
            blockers += "Installed-version marker is empty or unreadable"
        }

        return RuntimeReadiness(
            readyForServicePreflight = blockers.isEmpty(),
            installedVersion = version,
            blockers = blockers.toList()
        )
    }
}
