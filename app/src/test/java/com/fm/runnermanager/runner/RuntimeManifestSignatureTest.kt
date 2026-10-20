package com.fm.runnermanager.runner

import org.junit.Assert.*
import org.junit.Test

class RuntimeManifestSignatureTest {
    @Test fun noTrustedKeyIsNotTreatedAsVerified() {
        assertEquals(
            RuntimeManifestSignature.Result.Unverifiable,
            RuntimeManifestSignature.verify("{}".toByteArray(), "c2ln", "")
        )
    }

    @Test fun missingSignatureFailsWhenKeyIsConfigured() {
        val result = RuntimeManifestSignature.verify("{}".toByteArray(), null, "not-a-valid-public-key")
        assertTrue(result is RuntimeManifestSignature.Result.Invalid)
    }

    @Test fun malformedSignatureFailsClosed() {
        val result = RuntimeManifestSignature.verify("{}".toByteArray(), "%%%", "not-a-valid-public-key")
        assertTrue(result is RuntimeManifestSignature.Result.Invalid)
    }
}
