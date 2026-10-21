package com.fm.runnermanager.runtime

/**
 * Strict JSON parser for runtime manifest verification.
 *
 * - Rejects leading UTF-8 BOM.
 * - Rejects invalid UTF-8 (CodingErrorAction.REPORT).
 * - Rejects duplicate keys at every object depth (decoded-key comparison).
 * - Requires all object keys to be JSON strings.
 * - Rejects trailing content after the root value.
 * - Rejects unpaired surrogates in \uXXXX escapes.
 * - Preserves integer tokens vs decimal/exponent tokens.
 * - Enforces ALL limits during scanning, not at end of input.
 *
 * All failures are fail-closed via StrictJsonException.
 */
class StrictJsonException(message: String, val pos: Int = -1) :
    Exception(if (pos >= 0) "$message at byte $pos" else message)

object StrictJson {
    const val MAX_INPUT_BYTES = 64 * 1024
    const val MAX_DEPTH = 64
    const val MAX_STRING_RAW_BYTES = 8 * 1024
    const val MAX_STRING_DECODED_UNITS = 4 * 1024
    const val MAX_OBJECT_MEMBERS = 32
    const val MAX_ARRAY_LENGTH = 64
    const val MAX_NUMBER_TOKEN_LENGTH = 40

    fun parseTopLevelObject(bytes: ByteArray): JsonObject {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw StrictJsonException("Input exceeds $MAX_INPUT_BYTES bytes")
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            throw StrictJsonException("UTF-8 BOM is not allowed", 0)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
        } catch (e: java.nio.charset.CharacterCodingException) {
            // Byte offset not available from the JDK decoder; pos == -1 signals that.
            throw StrictJsonException("Invalid UTF-8 input")
        }

        val parser = Parser(bytes)
        parser.skipWs()
        val value = parser.parseValue(0)
        parser.skipWs()
        if (!parser.atEnd()) {
            throw StrictJsonException("Trailing content after root", parser.pos)
        }
        if (value !is JsonObject) {
            throw StrictJsonException("Top-level value must be an object", 0)
        }
        return value
    }

    private class Parser(val bytes: ByteArray) {
        var pos: Int = 0

        fun atEnd(): Boolean = pos >= bytes.size
        private fun peek(): Int = if (atEnd()) -1 else bytes[pos].toInt() and 0xFF
        private fun fail(msg: String): Nothing = throw StrictJsonException(msg, pos)

        private fun expect(c: Char) {
            if (atEnd() || bytes[pos].toInt() != c.code) fail("Expected '$c'")
            pos++
        }

        fun skipWs() {
            while (!atEnd()) {
                when (bytes[pos].toInt() and 0xFF) {
                    0x20, 0x09, 0x0A, 0x0D -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): JsonValue {
            if (atEnd()) fail("Unexpected end of input")
            return when (peek()) {
                '{'.code -> {
                    if (depth >= MAX_DEPTH) fail("Nesting depth exceeds $MAX_DEPTH")
                    parseObject(depth + 1)
                }
                '['.code -> {
                    if (depth >= MAX_DEPTH) fail("Nesting depth exceeds $MAX_DEPTH")
                    parseArray(depth + 1)
                }
                '"'.code -> parseString()
                't'.code -> parseLiteral("true", JsonBoolean(true))
                'f'.code -> parseLiteral("false", JsonBoolean(false))
                'n'.code -> parseLiteral("null", JsonNull)
                '-'.code, in '0'.code..'9'.code -> parseNumber()
                else -> fail("Unexpected character 0x%02X".format(peek()))
            }
        }

        private fun parseLiteral(text: String, value: JsonValue): JsonValue {
            for (c in text) {
                if (atEnd() || bytes[pos].toInt() != c.code) fail("Invalid literal")
                pos++
            }
            return value
        }

        private fun parseObject(depth: Int): JsonObject {
            expect('{')
            skipWs()
            val entries = LinkedHashMap<String, JsonValue>()
            if (peek() == '}'.code) {
                pos++
                return JsonObject(entries)
            }
            while (true) {
                skipWs()
                if (peek() != '"'.code) fail("Object key must be a string")
                val key = (parseString() as JsonString).value
                // Duplicate check wins over member-count check.
                if (entries.containsKey(key)) fail("Duplicate key: \"$key\"")
                if (entries.size >= MAX_OBJECT_MEMBERS) {
                    fail("Object member count exceeds $MAX_OBJECT_MEMBERS")
                }
                skipWs()
                expect(':')
                skipWs()
                val value = parseValue(depth)
                entries[key] = value
                skipWs()
                when (peek()) {
                    ','.code -> { pos++; continue }
                    '}'.code -> { pos++; return JsonObject(entries) }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            expect('[')
            skipWs()
            val items = mutableListOf<JsonValue>()
            if (peek() == ']'.code) {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWs()
                if (items.size >= MAX_ARRAY_LENGTH) {
                    fail("Array length exceeds $MAX_ARRAY_LENGTH")
                }
                items += parseValue(depth)
                skipWs()
                when (peek()) {
                    ','.code -> { pos++; continue }
                    ']'.code -> { pos++; return JsonArray(items) }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): JsonString {
            expect('"')
            val contentStart = pos
            val sb = StringBuilder()
            var decodedUnits = 0

            // Enforce both limits during scanning, not at string close.
            fun checkRawLimit() {
                if (pos - contentStart > MAX_STRING_RAW_BYTES) {
                    fail("String exceeds $MAX_STRING_RAW_BYTES raw bytes")
                }
            }
            fun bumpUnits(n: Int) {
                decodedUnits += n
                if (decodedUnits > MAX_STRING_DECODED_UNITS) {
                    fail("String exceeds $MAX_STRING_DECODED_UNITS decoded units")
                }
            }

            while (true) {
                if (atEnd()) fail("Unterminated string")
                val b = peek()
                when {
                    b == '"'.code -> {
                        pos++
                        return JsonString(sb.toString())
                    }
                    b == '\\'.code -> {
                        pos++
                        checkRawLimit()
                        if (atEnd()) fail("Unterminated escape")
                        val esc = peek().toChar()
                        pos++
                        checkRawLimit()
                        when (esc) {
                            '"'  -> { sb.append('"');        bumpUnits(1) }
                            '\\' -> { sb.append('\\');       bumpUnits(1) }
                            '/'  -> { sb.append('/');        bumpUnits(1) }
                            'b'  -> { sb.append('\b');       bumpUnits(1) }
                            'f'  -> { sb.append('\u000C');   bumpUnits(1) }
                            'n'  -> { sb.append('\n');       bumpUnits(1) }
                            'r'  -> { sb.append('\r');       bumpUnits(1) }
                            't'  -> { sb.append('\t');       bumpUnits(1) }
                            'u'  -> {
                                val cp = readHex4()
                                checkRawLimit()
                                if (Character.isHighSurrogate(cp.toChar())) {
                                    if (pos + 6 > bytes.size ||
                                        bytes[pos].toInt() != '\\'.code ||
                                        bytes[pos + 1].toInt() != 'u'.code
                                    ) fail("Unpaired high surrogate")
                                    pos += 2
                                    checkRawLimit()
                                    val low = readHex4()
                                    checkRawLimit()
                                    if (!Character.isLowSurrogate(low.toChar())) {
                                        fail("Invalid surrogate pair")
                                    }
                                    sb.append(cp.toChar())
                                    sb.append(low.toChar())
                                    bumpUnits(2)
                                } else if (Character.isLowSurrogate(cp.toChar())) {
                                    fail("Unpaired low surrogate")
                                } else {
                                    sb.append(cp.toChar())
                                    bumpUnits(1)
                                }
                            }
                            else -> fail("Invalid escape: \\$esc")
                        }
                    }
                    b < 0x20 -> fail("Unescaped control character U+%04X".format(b))
                    b < 0x80 -> {
                        pos++
                        checkRawLimit()
                        sb.append(b.toChar())
                        bumpUnits(1)
                    }
                    else -> {
                        val cp = readUtf8MultiByte(b)
                        checkRawLimit()
                        if (cp >= 0x10000) {
                            sb.appendCodePoint(cp)
                            bumpUnits(2)
                        } else {
                            sb.append(cp.toChar())
                            bumpUnits(1)
                        }
                    }
                }
            }
        }

        private fun readHex4(): Int {
            if (pos + 4 > bytes.size) fail("Truncated \\u escape")
            var v = 0
            repeat(4) {
                val c = bytes[pos].toInt() and 0xFF
                val d = when {
                    c in '0'.code..'9'.code -> c - '0'.code
                    c in 'a'.code..'f'.code -> c - 'a'.code + 10
                    c in 'A'.code..'F'.code -> c - 'A'.code + 10
                    else -> fail("Invalid hex digit in \\u escape")
                }
                v = (v shl 4) or d
                pos++
            }
            return v
        }

        private fun readUtf8MultiByte(first: Int): Int {
            pos++
            return when (first) {
                in 0xC2..0xDF -> {
                    val b2 = nextContinuation()
                    ((first and 0x1F) shl 6) or (b2 and 0x3F)
                }
                in 0xE0..0xEF -> {
                    val b2 = nextContinuation()
                    val b3 = nextContinuation()
                    ((first and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                }
                in 0xF0..0xF4 -> {
                    val b2 = nextContinuation()
                    val b3 = nextContinuation()
                    val b4 = nextContinuation()
                    ((first and 0x07) shl 18) or
                        ((b2 and 0x3F) shl 12) or
                        ((b3 and 0x3F) shl 6) or
                        (b4 and 0x3F)
                }
                else -> fail("Invalid UTF-8 lead byte 0x%02X".format(first))
            }
        }

        private fun nextContinuation(): Int {
            if (atEnd()) fail("Unexpected end in UTF-8 sequence")
            val b = bytes[pos].toInt() and 0xFF
            if (b !in 0x80..0xBF) fail("Invalid UTF-8 continuation 0x%02X".format(b))
            pos++
            return b
        }

        private fun parseNumber(): JsonNumber {
            val start = pos
            var hasDecimal = false
            var hasExponent = false

            fun bump() {
                pos++
                if (pos - start > MAX_NUMBER_TOKEN_LENGTH) {
                    fail("Number token exceeds $MAX_NUMBER_TOKEN_LENGTH bytes")
                }
            }

            if (peek() == '-'.code) bump()
            when {
                peek() == '0'.code -> bump()
                peek() in '1'.code..'9'.code -> {
                    bump()
                    while (peek() in '0'.code..'9'.code) bump()
                }
                else -> fail("Invalid number")
            }
            if (peek() == '.'.code) {
                hasDecimal = true
                bump()
                if (peek() !in '0'.code..'9'.code) fail("Invalid fraction")
                while (peek() in '0'.code..'9'.code) bump()
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                hasExponent = true
                bump()
                if (peek() == '+'.code || peek() == '-'.code) bump()
                if (peek() !in '0'.code..'9'.code) fail("Invalid exponent")
                while (peek() in '0'.code..'9'.code) bump()
            }
            val token = String(bytes, start, pos - start, Charsets.US_ASCII)
            return if (hasDecimal || hasExponent) JsonNumber.DecimalValue(token)
            else JsonNumber.IntegerValue(token)
        }
    }
}
