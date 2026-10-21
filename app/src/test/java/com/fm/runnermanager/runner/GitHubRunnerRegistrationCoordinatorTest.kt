package com.fm.runnermanager.runner

import org.junit.Assert.*
import org.junit.Test

class GitHubRunnerRegistrationCoordinatorTest {
    @Test
    fun oneShotGrantCanOnlyBeConsumedOnce() {
        val grant = GitHubRunnerRegistrationCoordinator.OneShotGrant(
            token = "test-registration-secret",
            expiresAt = "2099-01-01T00:00:00Z"
        )

        assertEquals("test-registration-secret", grant.consume())
        assertThrows(IllegalStateException::class.java) { grant.consume() }
    }

    @Test
    fun oneShotGrantRedactsSecretFromStringRepresentation() {
        val grant = GitHubRunnerRegistrationCoordinator.OneShotGrant(
            token = "never-print-this-token",
            expiresAt = null
        )

        assertFalse(grant.toString().contains("never-print-this-token"))
        assertTrue(grant.toString().contains("[REDACTED]"))
    }

    @Test
    fun discardedGrantCannotBeConsumed() {
        val grant = GitHubRunnerRegistrationCoordinator.OneShotGrant(
            token = "discarded-secret",
            expiresAt = null
        )
        grant.discard()

        assertThrows(IllegalStateException::class.java) { grant.consume() }
        assertFalse(grant.toString().contains("discarded-secret"))
    }
}
