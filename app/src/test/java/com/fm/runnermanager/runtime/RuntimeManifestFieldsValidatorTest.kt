package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestFieldsValidatorTest {
    private val valid = RuntimeManifestFields(
        version = "runtime-2.337.0",
        archiveUrl = "https://downloads.example.invalid/runtime.tar.gz",
        sha256 = "a".repeat(64)
    )

    @Test
    fun acceptsWellFormedHttpsManifestFields() {
        assertEquals(RuntimeManifestFieldsValidator.Result.Valid,
            RuntimeManifestFieldsValidator.validate(valid))
    }

    @Test
    fun acceptsHttpsUrlWithQueryParameters() {
        val fields = valid.copy(archiveUrl = "https://downloads.example.invalid/runtime.tar.gz?sig=abc")
        assertEquals(RuntimeManifestFieldsValidator.Result.Valid,
            RuntimeManifestFieldsValidator.validate(fields))
    }

    @Test
    fun rejectsBlankVersion() {
        assertInvalid(valid.copy(version = " "))
    }

    @Test
    fun rejectsVersionWithWhitespaceOrUnsupportedCharacters() {
        assertInvalid(valid.copy(version = "runtime version"))
        assertInvalid(valid.copy(version = "../runtime"))
    }

    @Test
    fun rejectsNonHttpsArchiveUrls() {
        assertInvalid(valid.copy(archiveUrl = "http://downloads.example.invalid/runtime.tar.gz"))
        assertInvalid(valid.copy(archiveUrl = "file:///tmp/runtime.tar.gz"))
    }

    @Test
    fun rejectsMalformedOrHostlessUrls() {
        assertInvalid(valid.copy(archiveUrl = "https://"))
        assertInvalid(valid.copy(archiveUrl = "not a url"))
    }

    @Test
    fun rejectsUrlWithEmbeddedCredentials() {
        assertInvalid(valid.copy(archiveUrl = "https://user:pass@downloads.example.invalid/runtime.tar.gz"))
    }

    @Test
    fun rejectsUrlWithFragment() {
        assertInvalid(valid.copy(archiveUrl = "https://downloads.example.invalid/runtime.tar.gz#part"))
    }

    @Test
    fun rejectsSha256WithWrongLengthOrNonHexCharacters() {
        assertInvalid(valid.copy(sha256 = "a".repeat(63)))
        assertInvalid(valid.copy(sha256 = "g".repeat(64)))
        assertInvalid(valid.copy(sha256 = ""))
    }

    @Test
    fun rejectsSurroundingWhitespaceInUrl() {
        assertInvalid(valid.copy(archiveUrl = " https://downloads.example.invalid/runtime.tar.gz "))
    }

    private fun assertInvalid(fields: RuntimeManifestFields) {
        assertTrue(
            "Expected invalid fields: $fields",
            RuntimeManifestFieldsValidator.validate(fields) is RuntimeManifestFieldsValidator.Result.Invalid
        )
    }
}
