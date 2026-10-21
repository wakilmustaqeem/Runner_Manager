package com.fm.runnermanager.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {

    private fun expectReject(json: String, reason: String = "") {
        try {
            StrictJson.parseTopLevelObject(json.toByteArray(Charsets.UTF_8))
            fail("Expected rejection: $reason (input=$json)")
        } catch (_: StrictJsonException) {
            // ok
        }
    }

    private fun expectAccept(json: String): JsonObject =
        StrictJson.parseTopLevelObject(json.toByteArray(Charsets.UTF_8))

    // --- Top-level shape

    @Test fun emptyObject() {
        assertEquals(0, expectAccept("{}").entries.size)
    }

    @Test fun trailingContentRejected() {
        expectReject("{\"a\":1} garbage")
    }

    @Test fun arrayAtTopRejected() {
        expectReject("[1,2,3]")
    }

    @Test fun trailingWhitespaceAccepted() {
        expectAccept("  {\"a\":1}  \n")
    }

    // --- BOM / UTF-8 / control chars

    @Test fun leadingBomRejected() {
        expectReject("\uFEFF{}")
    }

    @Test fun malformedUtf8Rejected() {
        val bad = byteArrayOf(
            0x7B, 0x22, 0x61, 0x22, 0x3A,
            0xC3.toByte(), 0x28, 0x7D,
        )
        try {
            StrictJson.parseTopLevelObject(bad)
            fail("Expected rejection for malformed UTF-8")
        } catch (_: StrictJsonException) {
            // ok
        }
    }

    @Test fun rawControlCharRejected() {
        expectReject("{\"a\":\"\u0001\"}")
    }

    // --- Duplicate keys

    @Test fun duplicateTopLevelRejected() {
        expectReject("{\"a\":1,\"a\":2}")
    }

    @Test fun duplicateEscapedCollisionRejected() {
        expectReject("{\"key\":1,\"\\u006bey\":2}")
    }

    @Test fun duplicateNestedRejected() {
        expectReject("{\"o\":{\"k\":1,\"\\u006b\":2}}")
    }

    // --- Object keys must be strings

    @Test fun numericKeyRejected() {
        expectReject("{1:2}")
    }

    @Test fun booleanKeyRejected() {
        expectReject("{true:2}")
    }

    @Test fun nullKeyRejected() {
        expectReject("{null:2}")
    }

    // --- Number classification and conversion

    @Test fun integerZero() {
        val v = expectAccept("{\"v\":0}").get("v") as JsonNumber.IntegerValue
        assertEquals(0L, v.toLongExact())
    }

    @Test fun negativeZero() {
        val v = expectAccept("{\"v\":-0}").get("v") as JsonNumber.IntegerValue
        assertEquals(0L, v.toLongExact())
    }

    @Test fun longMinValue() {
        val v = expectAccept("{\"v\":-9223372036854775808}")
            .get("v") as JsonNumber.IntegerValue
        assertEquals(Long.MIN_VALUE, v.toLongExact())
    }

    @Test fun positiveOverflowYieldsNull() {
        val v = expectAccept("{\"v\":9223372036854775808}")
            .get("v") as JsonNumber.IntegerValue
        assertNull(v.toLongExact())
    }

    @Test fun decimalIsDecimal() {
        assertTrue(expectAccept("{\"v\":1.0}").get("v") is JsonNumber.DecimalValue)
        assertTrue(expectAccept("{\"v\":1e2}").get("v") is JsonNumber.DecimalValue)
        assertTrue(expectAccept("{\"v\":-1.5e-3}").get("v") is JsonNumber.DecimalValue)
    }

    @Test fun stringIsNotInteger() {
        assertTrue(expectAccept("{\"v\":\"1\"}").get("v") is JsonString)
    }

    // --- Number grammar

    @Test fun leadingPlusRejected() {
        expectReject("{\"v\":+1}")
    }

    @Test fun leadingZeroRejected() {
        expectReject("{\"v\":01}")
    }

    @Test fun bareDotRejected() {
        expectReject("{\"v\":.5}")
    }

    @Test fun trailingDotRejected() {
        expectReject("{\"v\":1.}")
    }

    @Test fun bareExponentRejected() {
        expectReject("{\"v\":1e}")
    }

    @Test fun nanRejected() {
        expectReject("{\"v\":NaN}")
    }

    // --- Limits

    @Test fun depth64Accepted() {
        // 64 nested containers: outer + 63 more
        val ok = "{\"a\":" + "{\"a\":".repeat(62) + "{\"a\":1}" + "}".repeat(63)
        expectAccept(ok)
    }

    @Test fun depth65Rejected() {
        val bad = "{\"a\":" + "{\"a\":".repeat(63) + "{\"a\":1}" + "}".repeat(64)
        expectReject(bad, "depth 65")
    }

    @Test fun inputSizeOverflowRejected() {
        val big = "{\"a\":\"" + "x".repeat(StrictJson.MAX_INPUT_BYTES) + "\"}"
        expectReject(big)
    }

    @Test fun stringTooLongRejected() {
        val s = "{\"a\":\"" + "x".repeat(StrictJson.MAX_STRING_RAW_BYTES + 1) + "\"}"
        expectReject(s)
    }

    @Test fun tooManyMembersRejected() {
        val members = (0..StrictJson.MAX_OBJECT_MEMBERS)
            .joinToString(",") { "\"k$it\":1" }
        expectReject("{$members}")
    }

    @Test fun tooManyArrayElementsRejected() {
        val items = (0..StrictJson.MAX_ARRAY_LENGTH)
            .joinToString(",") { "1" }
        expectReject("{\"a\":[$items]}")
    }

    @Test fun numberTokenTooLongRejected() {
        val digits = "1" + "0".repeat(StrictJson.MAX_NUMBER_TOKEN_LENGTH)
        expectReject("{\"v\":$digits}")
    }

    // --- Escapes and surrogates

    @Test fun validSurrogatePairAccepted() {
        expectAccept("{\"a\":\"\\uD83D\\uDE00\"}")
    }

    @Test fun loneHighSurrogateRejected() {
        expectReject("{\"a\":\"\\uD800\"}")
    }

    @Test fun loneLowSurrogateRejected() {
        expectReject("{\"a\":\"\\uDC00\"}")
    }

    @Test fun highFollowedByNonLowRejected() {
        expectReject("{\"a\":\"\\uD800\\u0041\"}")
    }

    @Test fun invalidEscapeRejected() {
        expectReject("{\"a\":\"\\x\"}")
    }

    @Test fun escapeDecodesToCharNotNumber() {
        // Guards against the StringBuilder.append(Int) bug:
        // "\u0041" must decode to "A", not "65".
        val obj = expectAccept("{\"\\u0041\":1}")
        assertTrue(obj.has("A"))
        assertFalse(obj.has("65"))
    }

    @Test fun surrogatePairDecodesToChar() {
        val obj = expectAccept("{\"\\uD83D\\uDE00\":1}")
        assertTrue(obj.has("\uD83D\uDE00"))
    }

    // --- Duplicate beats member-count

    @Test fun duplicateWinsOverMemberLimit() {
        // 32 unique keys followed by a duplicate of k0.
        // Expected: duplicate-key error (checked first), NOT member-count error.
        val keys = (0 until StrictJson.MAX_OBJECT_MEMBERS)
            .joinToString(",") { "\"k$it\":1" }
        val json = "{$keys,\"k0\":1}"
        try {
            StrictJson.parseTopLevelObject(json.toByteArray(Charsets.UTF_8))
            fail("Expected StrictJsonException but input was accepted")
        } catch (e: StrictJsonException) {
            val msg = e.message ?: fail("Exception message was null")
            assertTrue(
                "Expected duplicate-key error, got: $msg",
                msg.contains("Duplicate key"),
            )
            assertFalse(
                "Member-count limit must not fire before duplicate detection",
                msg.contains("member count"),
            )
        }
    }

    // --- Limits fire during scanning, not only at string close

    @Test fun decodedLimitFiresBeforeStringClose() {
        // 5000 ASCII chars -> decoded limit 4096 must fire well before closing quote.
        val json = "{\"a\":\"" + "x".repeat(5000) + "\"}"
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            StrictJson.parseTopLevelObject(bytes)
            fail("Expected rejection")
        } catch (e: StrictJsonException) {
            val closingQuotePos = bytes.size - 2
            assertTrue(
                "Decoded limit should fire during scanning; pos=@@{e.pos}, closing=@@{closingQuotePos}",
                e.pos in 0 until closingQuotePos,
            )
        }
    }

    @Test fun rawLimitFiresBeforeStringClose() {
        // 5000 four-byte UTF-8 chars -> raw limit 8192 must fire well before closing quote.
        val unit = "\uD83D\uDE00"
        val json = "{\"a\":\"" + unit.repeat(5000) + "\"}"
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            StrictJson.parseTopLevelObject(bytes)
            fail("Expected rejection")
        } catch (e: StrictJsonException) {
            val closingQuotePos = bytes.size - 2
            assertTrue(
                "Raw/decoded limit should fire during scanning; pos=@@{e.pos}, closing=@@{closingQuotePos}",
                e.pos in 0 until closingQuotePos,
            )
        }
    }
}
