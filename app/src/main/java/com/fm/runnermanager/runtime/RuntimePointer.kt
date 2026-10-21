package com.fm.runnermanager.runtime

import java.util.UUID

/**
 * Pointer schema version 2.
 * Field name is `schemaVersion` to remain consistent with the existing
 * v1 pointer that the current installer still writes.
 */
data class RuntimePointer(
    val schemaVersion: Int,
    val transactionId: String,
    val version: Long,
    val directoryName: String,
    val sha256: String,
    val activatedAtEpochSeconds: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 2
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val UUID_RE = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        fun serialize(p: RuntimePointer): ByteArray {
            validate(p)  // fail-closed before any string building
            val sb = StringBuilder(320)
            sb.append('{')
            sb.append("\"schemaVersion\":").append(p.schemaVersion).append(',')
            sb.append("\"transactionId\":").append(quote(p.transactionId)).append(',')
            sb.append("\"version\":").append(p.version).append(',')
            sb.append("\"directoryName\":").append(quote(p.directoryName)).append(',')
            sb.append("\"sha256\":").append(quote(p.sha256)).append(',')
            sb.append("\"activatedAtEpochSeconds\":").append(p.activatedAtEpochSeconds)
            sb.append('}')
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        fun parse(bytes: ByteArray): RuntimePointer {
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

            val p = RuntimePointer(
                schemaVersion = reqInt("schemaVersion"),
                transactionId = reqString("transactionId"),
                version = reqLong("version"),
                directoryName = reqString("directoryName"),
                sha256 = reqString("sha256"),
                activatedAtEpochSeconds = reqLong("activatedAtEpochSeconds"),
            )
            validate(p)
            return p
        }

        fun validate(p: RuntimePointer) {
            require(p.schemaVersion == SCHEMA_VERSION) {
                "schemaVersion must be $SCHEMA_VERSION, got ${p.schemaVersion}"
            }
            require(UUID_RE.matches(p.transactionId)) {
                "Invalid transactionId"
            }
            require(p.version > 0L) { "version must be positive" }
            require(SHA256_HEX.matches(p.sha256)) { "Invalid sha256" }
            require(p.directoryName == "runtime-${p.version}-${p.sha256.take(12)}") {
                "directoryName must be runtime-<version>-<sha[0..12]>"
            }
            require(p.directoryName.isNotEmpty()) { "directoryName is empty" }
            require(!p.directoryName.contains('/')) { "directoryName contains '/'" }
            require(!p.directoryName.contains('\u0000')) { "directoryName contains NUL" }
            require(p.activatedAtEpochSeconds > 0L) {
                "activatedAtEpochSeconds must be positive"
            }
        }

        fun newTransactionId(): String = UUID.randomUUID().toString()

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
