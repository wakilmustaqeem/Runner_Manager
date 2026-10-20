package com.fm.runnermanager.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialVaultInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedTokenRoundTripsAndDoesNotAppearAsPlaintext() {
        val slot = 9101
        val token = "TEST_ONLY_NOT_A_REAL_GITHUB_TOKEN_9101"
        val vault = CredentialVault(context)
        val preferences = context.getSharedPreferences("encrypted_credential_vault", android.content.Context.MODE_PRIVATE)
        try {
            vault.clear(slot)
            vault.save(slot, token)
            assertEquals(token, vault.load(slot))
            val storedValue = preferences.getString("account_slot_$slot", null)
            assertFalse(storedValue.isNullOrBlank())
            assertFalse(storedValue!!.contains(token))
        } finally {
            vault.clear(slot)
        }
        assertNull(vault.load(slot))
    }

    @Test
    fun slotsRemainIsolated() {
        val firstSlot = 9102
        val secondSlot = 9103
        val vault = CredentialVault(context)
        try {
            vault.clear(firstSlot)
            vault.clear(secondSlot)
            vault.save(firstSlot, "TEST_TOKEN_SLOT_A")
            vault.save(secondSlot, "TEST_TOKEN_SLOT_B")
            assertEquals("TEST_TOKEN_SLOT_A", vault.load(firstSlot))
            assertEquals("TEST_TOKEN_SLOT_B", vault.load(secondSlot))
            vault.clear(firstSlot)
            assertNull(vault.load(firstSlot))
            assertEquals("TEST_TOKEN_SLOT_B", vault.load(secondSlot))
        } finally {
            vault.clear(firstSlot)
            vault.clear(secondSlot)
        }
    }

    @Test
    fun clearRemovesSavedCredential() {
        val slot = 9104
        val vault = CredentialVault(context)
        vault.save(slot, "TEST_ONLY_CLEAR_ME")
        vault.clear(slot)
        assertNull(vault.load(slot))
    }
}
