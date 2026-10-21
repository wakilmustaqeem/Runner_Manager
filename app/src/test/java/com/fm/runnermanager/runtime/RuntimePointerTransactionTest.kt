package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class RuntimePointerTransactionTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var txn: RuntimePointerTransaction
    private var verifierResult: Boolean = true

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    @Before fun setUp() {
        root = tmp.newFolder("runtime-root")
        File(root, "runtime-10-aaaaaaaaaaaa").mkdirs()
        File(root, "runtime-20-bbbbbbbbbbbb").mkdirs()
        verifierResult = true
        txn = newTxn(minVersion = 1L)
    }

    private fun newTxn(minVersion: Long): RuntimePointerTransaction =
        RuntimePointerTransaction(
            root = root,
            minimumAllowedVersion = minVersion,
            verifyDirectory = { verifierResult },
            nowEpochSeconds = { 1_700_000_000L },
        )

    private fun pointerFile() = File(root, RuntimePointerTransaction.POINTER_NAME)
    private fun pendingFile() = File(root, RuntimePointerTransaction.PENDING_NAME)
    private fun previousFile() = File(root, RuntimePointerTransaction.PREVIOUS_NAME)
    private fun commitFile() = File(root, RuntimePointerTransaction.COMMIT_NAME)

    private fun writeRawPointer(
        file: File, version: Long, name: String, sha: String, txnId: String,
    ) {
        val p = RuntimePointer(
            schemaVersion = 2,
            transactionId = txnId,
            version = version,
            directoryName = name,
            sha256 = sha,
            activatedAtEpochSeconds = 1L,
        )
        file.writeBytes(RuntimePointer.serialize(p))
    }

    private fun writeRawCommit(
        file: File, txnId: String, version: Long, sha: String,
    ) {
        val m = CommitMarker(
            schemaVersion = 1,
            transactionId = txnId,
            pendingVersion = version,
            pendingSha256 = sha,
        )
        file.writeBytes(CommitMarker.serialize(m))
    }

    // ---- writeActive ------------------------------------------------

    @Test fun writeActive_happyPath() {
        val p = txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
        assertTrue(pointerFile().exists())
        assertFalse(pendingFile().exists())
        assertFalse(previousFile().exists())
        assertFalse(commitFile().exists())
        assertEquals(10L, p.version)
        assertEquals("runtime-10-aaaaaaaaaaaa", p.directoryName)
    }

    @Test fun writeActive_rejectsBelowFloor() {
        val floorTxn = newTxn(minVersion = 100L)
        try {
            floorTxn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
            fail("expected floor rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun writeActive_rejectsBadSha() {
        try {
            txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", "short")
            fail("expected sha rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun writeActive_rejectsNameVersionMismatch() {
        try {
            txn.writeActive(10L, "runtime-20-bbbbbbbbbbbb", shaA)
            fail("expected name/version rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun writeActive_rejectsStalePending() {
        pendingFile().writeText("{}")
        try {
            txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
            fail("expected stale-pending")
        } catch (_: IllegalStateException) { }
    }

    @Test fun writeActive_rejectsStaleCommit() {
        commitFile().writeText("{}")
        try {
            txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
            fail("expected stale-commit")
        } catch (_: IllegalStateException) { }
    }

    @Test fun writeActive_rejectsMissingRuntimeDir() {
        File(root, "runtime-20-bbbbbbbbbbbb").deleteRecursively()
        try {
            txn.writeActive(20L, "runtime-20-bbbbbbbbbbbb", shaB)
            fail("expected missing-dir rejection")
        } catch (_: java.io.IOException) { }
    }

    @Test fun writeActive_generatesUniqueTransactionIds() {
        val p1 = txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
        val p2 = txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
        assertNotEquals(p1.transactionId, p2.transactionId)
    }

    // ---- recover ----------------------------------------------------

    @Test fun recover_emptyRoot_safeFailure() {
        pointerFile().delete()
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    @Test fun recover_normalState_returnsActive() {
        val p = txn.writeActive(10L, "runtime-10-aaaaaaaaaaaa", shaA)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(p.transactionId,
            (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.transactionId)
    }

    @Test fun recover_row1_pendingPresentActiveValid() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, UUID.randomUUID().toString())
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(pendingFile().exists())
    }

    @Test fun recover_row2_previousRestoredWhenActiveInvalid() {
        pointerFile().writeText("{bad")
        writeRawPointer(previousFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
    }

    @Test fun recover_row3_safeFailureWhenEverythingInvalid() {
        pointerFile().writeText("{bad")
        previousFile().writeText("{bad")
        pendingFile().writeText("{bad")
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    @Test fun recover_row4_rollForwardWhenPendingNewerAndBinds() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val t = UUID.randomUUID().toString()
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 20L, shaB)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(20L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
    }

    @Test fun recover_row5_noRollForwardWhenEqualVersion() {
        writeRawPointer(pointerFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, UUID.randomUUID().toString())
        val t = UUID.randomUUID().toString()
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 20L, shaB)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(20L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(pendingFile().exists())
        assertFalse(commitFile().exists())
    }

    @Test fun recover_row5_noRollForwardWhenShaMismatch() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val t = UUID.randomUUID().toString()
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 20L, shaC)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(commitFile().exists())
    }

    @Test fun recover_row5_noRollForwardWhenVersionMismatch() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val t = UUID.randomUUID().toString()
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 21L, shaB)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
    }

    @Test fun recover_row6_committedTxnCleanupOnly() {
        val t = UUID.randomUUID().toString()
        writeRawPointer(pointerFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 20L, shaB)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(t, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.transactionId)
        assertEquals(20L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(pendingFile().exists())
        assertFalse(commitFile().exists())
    }

    @Test fun recover_row7_committedActiveInvalid_rollbackOnly() {
        val t = UUID.randomUUID().toString()
        writeRawPointer(pointerFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawPointer(previousFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        writeRawCommit(commitFile(), t, 20L, shaB)
        File(root, "runtime-20-bbbbbbbbbbbb").deleteRecursively()
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
    }

    @Test fun recover_row8_orphanCommitDeleted() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        writeRawCommit(commitFile(), UUID.randomUUID().toString(), 99L, shaC)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(commitFile().exists())
    }

    @Test fun recover_row9_invalidCommitActivePreserved() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        commitFile().writeText("{truncated")
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(commitFile().exists())
    }

    @Test fun recover_row10_invalidCommitPreviousRestored() {
        pointerFile().writeText("{bad")
        writeRawPointer(previousFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        commitFile().writeText("{truncated")
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
    }

    @Test fun recover_row11_allInvalid_safeFailure() {
        pointerFile().writeText("{bad")
        previousFile().writeText("{bad")
        commitFile().writeText("{bad")
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    @Test fun recover_previousBelowFloor_safeFailure() {
        val floorTxn = newTxn(minVersion = 50L)
        pointerFile().writeText("{bad")
        writeRawPointer(previousFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val r = floorTxn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    @Test fun recover_verifierFailure_invalidatesActive() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        verifierResult = false
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    @Test fun recover_idempotentSecondRun() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        pendingFile().writeText("{bad")
        txn.recover()
        val before = root.listFiles()!!
            .associate { it.name to it.readBytes().toList() }
        txn.recover()
        val after = root.listFiles()!!
            .associate { it.name to it.readBytes().toList() }
        assertEquals(before, after)
    }

    @Test fun recover_corruptPendingDeleted() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        pendingFile().writeText("{bad")
        txn.recover()
        assertFalse(pendingFile().exists())
    }

    @Test fun recover_symlinkPointer_safeFailure() {
        val real = File(tmp.root, "real-pointer")
        writeRawPointer(real, 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        java.nio.file.Files.createSymbolicLink(
            pointerFile().toPath(), real.toPath(),
        )
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.SafeFailure)
    }

    // ---- Fixes ------------------------------------------------------

    @Test fun pointer_rejectsSchemaVersionOverflow() {
        val bad = """{"schemaVersion":4294967298,"transactionId":"${UUID.randomUUID()}",
            "version":1,"directoryName":"runtime-1-${shaA.take(12)}",
            "sha256":"$shaA","activatedAtEpochSeconds":1}""".toByteArray()
        try {
            RuntimePointer.parse(bad)
            fail("expected rejection")
        } catch (_: Exception) { }
    }

    @Test fun commitSerializeRejectsBadUuid() {
        val m = CommitMarker(
            schemaVersion = 1,
            transactionId = "not-a-uuid",
            pendingVersion = 1L,
            pendingSha256 = shaA,
        )
        try {
            CommitMarker.serialize(m)
            fail("expected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun commitBindsRequiresVersionAndSha() {
        writeRawPointer(pointerFile(), 10L, "runtime-10-aaaaaaaaaaaa", shaA, UUID.randomUUID().toString())
        val t = UUID.randomUUID().toString()
        writeRawPointer(pendingFile(), 20L, "runtime-20-bbbbbbbbbbbb", shaB, t)
        writeRawCommit(commitFile(), t, 21L, shaB)
        val r = txn.recover()
        assertTrue(r is RuntimePointerTransaction.RecoveryResult.Active)
        assertEquals(10L, (r as RuntimePointerTransaction.RecoveryResult.Active).pointer.version)
        assertFalse(commitFile().exists())
    }
}
