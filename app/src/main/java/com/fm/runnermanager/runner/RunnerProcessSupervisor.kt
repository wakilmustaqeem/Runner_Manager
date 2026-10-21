package com.fm.runnermanager.runner

import java.io.File

/**
 * Owns at most one local runner process in this app process.
 *
 * This is a process primitive, not an Android foreground service: it does not
 * survive process death, recover after reboot, register a runner with GitHub,
 * or enforce policy at the runner's job-admission boundary. Keep UI controls
 * disabled until a foreground-service integration and those guarantees exist.
 *
 * Callers must supply a command built from a verified runtime and must never
 * log command arguments because they can contain short-lived registration tokens.
 */
internal class RunnerProcessSupervisor(
    private val outputSink: File = File("/dev/null"),
) {
    enum class Status { STOPPED, RUNNING, EXITED }

    data class Snapshot(
        val status: Status,
        val exitCode: Int? = null,
    )

    private val lock = Any()
    private var process: Process? = null
    private var lastExitCode: Int? = null

    fun snapshot(): Snapshot = synchronized(lock) {
        val current = process
        when {
            current == null -> Snapshot(Status.STOPPED, lastExitCode)
            current.isAlive -> Snapshot(Status.RUNNING)
            else -> {
                val code = runCatching { current.exitValue() }.getOrNull()
                lastExitCode = code
                process = null
                Snapshot(Status.EXITED, code)
            }
        }
    }

    /**
     * Starts only after explicit user confirmation and a caller-supplied
     * verified-runtime gate. This method is intentionally not wired to UI.
     */
    fun start(
        explicitUserConfirmation: Boolean,
        runtimeVerified: () -> Boolean,
        commandFactory: () -> ProcessBuilder,
    ): Snapshot = synchronized(lock) {
        check(explicitUserConfirmation) { "Explicit confirmation is required" }
        check(runtimeVerified()) { "Verified runner runtime is required" }

        val current = process
        if (current != null && current.isAlive) {
            throw IllegalStateException("A runner process is already active")
        }
        if (current != null) {
            lastExitCode = runCatching { current.exitValue() }.getOrNull()
            process = null
        }
        check(outputSink.isFile && outputSink.canWrite()) {
            "Runner output sink is unavailable"
        }

        // Never pipe output into an unread buffer: a full pipe could stall the runner.
        // The default sink discards output to avoid leaking credentials into logs.
        val builder = commandFactory()
        builder.redirectErrorStream(true)
        builder.redirectOutput(outputSink)
        val launched = builder.start()
        process = launched
        lastExitCode = null
        Snapshot(Status.RUNNING)
    }

    /**
     * Requests termination of the owned process. It does not obtain a GitHub
     * removal token and therefore does not unregister the repository runner.
     */
    fun stop(explicitUserConfirmation: Boolean): Snapshot {
        check(explicitUserConfirmation) { "Explicit confirmation is required" }
        val current = synchronized(lock) {
            val owned = process
            if (owned == null || !owned.isAlive) {
                if (owned != null) {
                    lastExitCode = runCatching { owned.exitValue() }.getOrNull()
                    process = null
                }
                return@synchronized null
            }
            owned
        } ?: return snapshot()

        current.destroy()
        val waiter = Thread {
            runCatching { current.waitFor() }
        }.apply {
            name = "fm-runner-stop-wait"
            isDaemon = true
            start()
        }
        runCatching { waiter.join(2_000L) }
        if (current.isAlive) current.destroyForcibly()

        return snapshot()
    }
}
