package com.fm.runnermanager.runtime

/**
 * JSON value model used by StrictJson.
 *
 * Number tokens preserve their original byte representation so that
 * integer tokens can be distinguished from decimal/exponent tokens.
 */
sealed interface JsonValue

data class JsonObject(val entries: LinkedHashMap<String, JsonValue>) : JsonValue {
    fun has(key: String): Boolean = entries.containsKey(key)
    fun get(key: String): JsonValue? = entries[key]
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

sealed interface JsonNumber : JsonValue {
    val token: String

    data class IntegerValue(override val token: String) : JsonNumber {
        fun toLongExact(): Long? = token.toLongOrNull()
    }

    data class DecimalValue(override val token: String) : JsonNumber
}

data class JsonBoolean(val value: Boolean) : JsonValue

object JsonNull : JsonValue
