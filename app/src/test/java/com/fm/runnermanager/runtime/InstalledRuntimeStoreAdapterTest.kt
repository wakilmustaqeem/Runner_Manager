package com.fm.runnermanager.runtime

import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstalledRuntimeStoreAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("runtime-root")
    }

    @Test
    fun readActive_returnsNullWhenEmpty() {
        assertNull(InstalledRuntimeStore.readActive(root))
    }

    @Test
    fun readActiveForInstall_returnsNullOnFirstInstall() {
        assertNull(InstalledRuntimeStore.readActiveForInstall(root))
    }

    @Test
    fun recoverOnStartup_returnsNullOnFirstInstall() {
        assertNull(InstalledRuntimeStore.recoverOnStartup(root))
    }

    @Test
    fun readActive_rejectsV1Pointer() {
        File(root, "active-installed-runtime.json").writeText(
            """{"schemaVersion":1,"version":10,""" +
                """"directoryName":"runtime-10-aaaaaaaaaaaa",""" +
                """"sha256":"${"a".repeat(64)}"}""",
        )
        assertNull(InstalledRuntimeStore.readActive(root))
    }

    @Test
    fun readActiveForInstall_throwsOnV1Pointer() {
        File(root, "active-installed-runtime.json").writeText(
            """{"schemaVersion":1,"version":10,""" +
                """"directoryName":"runtime-10-aaaaaaaaaaaa",""" +
                """"sha256":"${"a".repeat(64)}"}""",
        )
        assertThrows(SecurityException::class.java) {
            InstalledRuntimeStore.readActiveForInstall(root)
        }
    }
}
