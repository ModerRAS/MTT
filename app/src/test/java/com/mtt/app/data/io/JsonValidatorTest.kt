package com.mtt.app.data.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for JsonValidator.
 */
class JsonValidatorTest {

    // ========== validate() Tests ==========

    @Test
    fun validate_flatJsonWithStringValues_returnsValid() {
        val result = JsonValidator.validate("""{"key":"value"}""")
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun validate_multipleFlatKeyValuePairs_returnsValid() {
        val result = JsonValidator.validate("""{"name":"test","version":"1.0"}""")
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun validate_blankString_returnsEmpty() {
        val result = JsonValidator.validate("   ")
        assertEquals(ValidationResult.EMPTY, result)
    }

    @Test
    fun validate_emptyObject_returnsEmpty() {
        val result = JsonValidator.validate("{}")
        assertEquals(ValidationResult.EMPTY, result)
    }

    @Test
    fun validate_malformedJson_returnsInvalidJson() {
        // Use text that is definitely not parseable JSON even in lenient mode
        val result = JsonValidator.validate("this is not json")
        assertEquals(ValidationResult.INVALID_JSON, result)
    }

    @Test
    fun validate_nestedObject_returnsNestedJson() {
        val result = JsonValidator.validate("""{"outer":{"inner":"v"}}""")
        assertEquals(ValidationResult.NESTED_JSON, result)
    }

    @Test
    fun validate_array_returnsNestedJson() {
        val result = JsonValidator.validate("[1,2,3]")
        assertEquals(ValidationResult.NESTED_JSON, result)
    }

    @Test
    fun validate_numericValue_returnsNonStringValues() {
        val result = JsonValidator.validate("""{"key":123}""")
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    @Test
    fun validate_booleanValue_returnsNonStringValues() {
        val result = JsonValidator.validate("""{"key":true}""")
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    // ========== isValidMtoolFormat() Tests ==========

    @Test
    fun isValidMtoolFormat_validJson_returnsTrue() {
        assertTrue(JsonValidator.isValidMtoolFormat("""{"key":"value"}"""))
    }

    @Test
    fun isValidMtoolFormat_multipleValidPairs_returnsTrue() {
        assertTrue(JsonValidator.isValidMtoolFormat("""{"a":"1","b":"2"}"""))
    }

    @Test
    fun isValidMtoolFormat_blankString_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("   "))
    }

    @Test
    fun isValidMtoolFormat_emptyObject_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("{}"))
    }

    @Test
    fun isValidMtoolFormat_nestedJson_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("""{"outer":{"inner":"v"}}"""))
    }

    @Test
    fun isValidMtoolFormat_array_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("[1,2,3]"))
    }

    @Test
    fun isValidMtoolFormat_numericValue_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("""{"key":123}"""))
    }

    @Test
    fun isValidMtoolFormat_booleanValue_returnsFalse() {
        assertFalse(JsonValidator.isValidMtoolFormat("""{"key":true}"""))
    }
}
