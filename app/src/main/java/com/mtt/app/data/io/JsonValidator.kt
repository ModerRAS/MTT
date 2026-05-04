package com.mtt.app.data.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validation result for MTool flat JSON format.
 */
enum class ValidationResult {
    /** JSON is a flat object with only string keys and string values */
    VALID,
    /** String is not valid JSON */
    INVALID_JSON,
    /** JSON contains nested objects or arrays (not flat) */
    NESTED_JSON,
    /** JSON has non-string values (numbers, booleans, null) */
    NON_STRING_VALUES,
    /** JSON object is empty (no entries) */
    EMPTY
}

/**
 * Validates that a JSON string conforms to MTool flat format:
 * `{"key1": "value1", "key2": "value2"}`
 *
 * Rules:
 * - Must be a JSON object (not array, not primitive)
 * - All keys must be strings
 * - All values must be strings (no numbers, booleans, null, nested objects/arrays)
 * - At least one entry (if empty → EMPTY)
 */
object JsonValidator {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = false
    }

    /**
     * Validates the JSON string against MTool format rules.
     */
    fun validate(json: String): ValidationResult {
        if (json.isBlank()) return ValidationResult.EMPTY

        val jsonElement: JsonElement = try {
            lenientJson.parseToJsonElement(json)
        } catch (e: Exception) {
            return ValidationResult.INVALID_JSON
        }

        if (jsonElement !is JsonObject) {
            return ValidationResult.NESTED_JSON
        }

        val obj = jsonElement
        if (obj.isEmpty()) return ValidationResult.EMPTY

        for ((_, value) in obj) {
            when {
                value is JsonObject -> return ValidationResult.NESTED_JSON
                value is kotlinx.serialization.json.JsonArray -> return ValidationResult.NESTED_JSON
                value is JsonPrimitive && !value.isString -> return ValidationResult.NON_STRING_VALUES
            }
        }

        return ValidationResult.VALID
    }

    /**
     * Returns true if the JSON string is valid MTool format.
     */
    fun isValidMtoolFormat(json: String): Boolean {
        return validate(json) == ValidationResult.VALID
    }
}
