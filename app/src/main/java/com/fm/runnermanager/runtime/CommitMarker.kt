package com.fm.runnermanager.runtime

data class CommitMarker(
    val schemaVersion: Int,
    val transactionId: String,
    val pendingVersion: Long,
    val pendingSha256: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val UUID_RE = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        fun serialize(m: CommitMarker): ByteArray {
            validate(m)  // fail-closed before any string building
            val sb = StringBuilder(220)
            sb.append('{')
            sb.append("\"schemaVersion\":").append(m.schemaVersion).append(',')
            sb.append("\"transactionId\":").append(quote(m.transactionId)).append(',')
            sb.append("\"pendingVersion\":").append(m.pendingVersion).append(',')
            sb.append("\"pendingSha256\":").append(quote(m.pendingSha256))
            sb.append('}')
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        fun parse(bytes: ByteArray): CommitMarker {
            val obj = StrictJson.parseTopLevelObject(bytes)

            fun reqString(name: String): String {
                val v = obj.get(name)
                    ?: throw IllegalArgumentException("Missing $name")
                if (v !is JsonString) {
                    throw IllegalArgumentException("$name must be a string")
                }
                return v.value
            }
            fun reqInt(name: String): Int {
                val v = obj.get(name)
                    ?: throw IllegalArgumentException("Missing $name")
                if (v !is JsonNumber.IntegerValue) {
                    throw IllegalArgumentException("$name must be an integer")
                }
                val l = v.toLongExact()
                    ?: throw IllegalArgumentException("$name overflows Long")
                if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) {
                    throw IllegalArgumentException("$name out of Int range: $l")
                }
                return l.toInt()
            }
            fun reqLong(name: String): Long {
                val v = obj.get(name)
                    ?: throw IllegalArgumentException("Missing $name")
                if (v !is JsonNumber.IntegerValue) {
                    throw IllegalArgumentException("$name must be an integer")
                }
                return v.toLongExact()
                    ?: throw IllegalArgumentException("$name overflows Long")
            }

            val m = CommitMarker(
                schemaVersion = reqInt("schemaVersion"),
                transactionId = reqString("transactionId"),
                pendingVersion = reqLong("pendingVersion"),
                pendingSha256 = reqString("pendingSha256"),
            )
            validate(m)
            return m
        }

        fun validate(m: CommitMarker) {
            require(m.schemaVersion == SCHEMA_VERSION) {
                "commit schemaVersion must be $SCHEMA_VERSION"
            }
            require(UUID_RE.matches(m.transactionId)) {
                "Invalid commit transactionId"
            }
            require(m.pendingVersion > 0L) { "pendingVersion must be positive" }
            require(SHA256_HEX.matches(m.pendingSha256)) { "Invalid pendingSha256" }
        }

        private fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
