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

    private fun installFixture(p: ProotRunnerCommand.Paths) {
        listOf(
            File(p.nativeLibraryDir, "libproot.so"),
            File(p.nativeLibraryDir, "libproot-loader.so"),
            File(p.nativeLibraryDir, "libproot-loader32.so"),
            File(p.runtimeDir, "rootfs/usr/bin/env"),
            File(p.runtimeDir, "home/runner/config.sh"),
            File(p.runtimeDir, "home/runner/run.sh")
        ).forEach { file -> file.parentFile?.mkdirs(); file.writeText("fixture") }
    }

    @Test fun missingRuntimeFailsClosedBeforeBuildingAProcess() {
        val p = paths(createTempDir())
        assertEquals(6, ProotRunnerCommand.runtimeProblems(p).size)
        assertThrows(IllegalStateException::class.java) { ProotRunnerCommand.run(p) }
    }

    @Test fun configurePinsOfficialRunnerArgumentsAndEphemeralMode() {
        val p = paths(createTempDir())
        installFixture(p)
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
        installFixture(p)
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunnerCommand.configure(p, "https://github.com/acme/../other", "runner", emptySet(), "token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProotRunnerCommand.configure(p, "https://github.com/acme/project", "runner", emptySet(), "")
        }
    }

    @Test fun listenerUsesOfficialRunScript() {
        val p = paths(createTempDir())
        installFixture(p)
        val builder = ProotRunnerCommand.run(p)
        assertTrue(builder.command().contains("/home/runner/run.sh"))
    }
}
