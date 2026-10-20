package com.fm.runnermanager.runner

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProotRunnerCommandTest {
    private fun paths(root: File) = ProotRunnerCommand.Paths(
        nativeLibraryDir = File(root, "lib"),
        cacheDir = File(root, "cache"),
        runtimeDir = File(root, "runtime")
    )

    @Test fun missingRuntimeFailsClosedBeforeProcessLaunch() {
        val p = paths(createTempDir())
        assertEquals(5, ProotRunnerCommand.runtimeProblems(p).size)
    }

    @Test fun configurePinsOfficialRunnerArgumentsAndEphemeralMode() {
        val root = createTempDir()
        val p = paths(root)
        val builder = ProotRunnerCommand.configure(
            p, "https://github.com/acme/project", "phone-runner",
            setOf("android", "arm64"), "test-registration-token", ephemeral = true
        )
        val command = builder.command()
        assertTrue(command.contains("--unattended"))
        assertTrue(command.contains("--replace"))
        assertTrue(command.contains("--url"))
        assertTrue(command.contains("--token"))
        assertTrue(command.contains("test-registration-token"))
        assertTrue(command.contains("--ephemeral"))
        assertEquals("libproot-loader.so", File(builder.environment()["PROOT_LOADER"]!!).name)
    }

    @Test fun invalidTargetAndEmptyTokenAreRejected() {
        val p = paths(createTempDir())
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunnerCommand.configure(p, "https://github.com/acme/../other", "runner", emptySet(), "token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunnerCommand.configure(p, "https://github.com/acme/project", "runner", emptySet(), "")
        }
    }

    @Test fun listenerUsesOfficialRunScript() {
        val builder = ProotRunnerCommand.run(paths(createTempDir()))
        assertTrue(builder.command().contains("/home/runner/run.sh"))
    }
}
