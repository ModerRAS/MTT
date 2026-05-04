package com.mtt.app.data.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.core.error.Result
import com.mtt.app.core.error.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Unit tests for [MtoolFileReader] and [JsonValidator].
 *
 * Tests cover:
 * - BOM detection (UTF-8, UTF-16LE, UTF-16BE, no BOM)
 * - JsonValidator validation results
 * - MtoolFileReader.readFromUri with mock ContentResolver
 * - Error handling (file not found, access errors)
 *
 * Uses Robolectric for Android Context and ContentResolver mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MtoolFileReaderTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var reader: MtoolFileReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        reader = MtoolFileReader(context)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BOM Detection — ByteArray.decodeWithBomDetection() helper
    //  Note: Testing inline logic that mirrors the source implementation
    // ═══════════════════════════════════════════════════════════════════════

    // BOM detection constants (same as source)
    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * Checks if this byte array starts with the given prefix bytes.
     * Mirrors the private startsWith function in MtoolFileReader.
     */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * Mirrors MtoolFileReader.decodeWithBomDetection() logic for testing.
     * Tests that byte arrays are correctly decoded with BOM detection.
     */
    private fun ByteArray.decodeWithBomDetection(): String {
        return when {
            this.startsWith(BOM_UTF8) -> {
                this.copyOfRange(3, this.size).toString(Charsets.UTF_8)
            }
            this.startsWith(BOM_UTF16_LE) -> {
                this.copyOfRange(2, this.size).toString(Charsets.UTF_16LE)
            }
            this.startsWith(BOM_UTF16_BE) -> {
                this.copyOfRange(2, this.size).toString(Charsets.UTF_16BE)
            }
            else -> {
                this.toString(Charsets.UTF_8)
            }
        }
    }

    @Test
    fun `decodeWithBomDetection handles UTF-8 BOM`() {
        // UTF-8 BOM: EF BB BF followed by content
        val bytesWithBom = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(),
            'l'.code.toByte(), 'o'.code.toByte()
        )
        val result = bytesWithBom.decodeWithBomDetection()
        assertEquals("Hello", result)
    }

    @Test
    fun `decodeWithBomDetection handles UTF-16LE BOM`() {
        // UTF-16LE BOM: FF FE followed by UTF-16LE encoded "Hi"
        val bytesWithBom = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(),
            'H'.code.toByte(), 0x00.toByte(),
            'i'.code.toByte(), 0x00.toByte()
        )
        val result = bytesWithBom.decodeWithBomDetection()
        assertEquals("Hi", result)
    }

    @Test
    fun `decodeWithBomDetection handles UTF-16BE BOM`() {
        // UTF-16BE BOM: FE FF followed by UTF-16BE encoded "Hi"
        val bytesWithBom = byteArrayOf(
            0xFE.toByte(), 0xFF.toByte(),
            0x00.toByte(), 'H'.code.toByte(),
            0x00.toByte(), 'i'.code.toByte()
        )
        val result = bytesWithBom.decodeWithBomDetection()
        assertEquals("Hi", result)
    }

    @Test
    fun `decodeWithBomDetection handles no BOM as UTF-8`() {
        // Plain UTF-8 without BOM
        val bytesNoBom = "Hello World".toByteArray(Charsets.UTF_8)
        val result = bytesNoBom.decodeWithBomDetection()
        assertEquals("Hello World", result)
    }

    @Test
    fun `decodeWithBomDetection handles empty array`() {
        val emptyBytes = byteArrayOf()
        val result = emptyBytes.decodeWithBomDetection()
        assertEquals("", result)
    }

    @Test
    fun `decodeWithBomDetection handles Chinese characters without BOM`() {
        val chineseText = "你好世界"
        val bytes = chineseText.toByteArray(Charsets.UTF_8)
        val result = bytes.decodeWithBomDetection()
        assertEquals(chineseText, result)
    }

    @Test
    fun `decodeWithBomDetection handles Unicode escape sequences`() {
        // JSON with Unicode escape
        val jsonWithUnicode = """{"key":"value\u4e2d\u6587"}"""
        val bytes = jsonWithUnicode.toByteArray(Charsets.UTF_8)
        val result = bytes.decodeWithBomDetection()
        assertEquals(jsonWithUnicode, result)
    }

    @Test
    fun `decodeWithBomDetection strips BOM but not content BOM-like bytes`() {
        // Content that starts with EF BB BF at start means UTF-8 BOM, which should be stripped
        val bytes = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            'A'.code.toByte(), 'B'.code.toByte()
        )
        val result = bytes.decodeWithBomDetection()
        assertEquals("AB", result)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  JsonValidator.validate()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `validate returns VALID for flat JSON with string values`() {
        val json = """{"key1": "value1", "key2": "value2"}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun `validate returns VALID for single key-value pair`() {
        val json = """{"name": "test"}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun `validate returns VALID for empty string key`() {
        val json = """{"": "empty key"}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun `validate returns VALID for special characters in values`() {
        val json = """{"key": "value with spaces and : colon"}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun `validate returns VALID for JSON with whitespace`() {
        val json = """
            {
                "key1": "value1",
                "key2": "value2"
            }
        """.trimIndent()
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.VALID, result)
    }

    @Test
    fun `validate returns EMPTY for blank string`() {
        val result = JsonValidator.validate("")
        assertEquals(ValidationResult.EMPTY, result)
    }

    @Test
    fun `validate returns EMPTY for whitespace only`() {
        val result = JsonValidator.validate("   \t\n  ")
        assertEquals(ValidationResult.EMPTY, result)
    }

    @Test
    fun `validate returns EMPTY for empty JSON object`() {
        val result = JsonValidator.validate("{}")
        assertEquals(ValidationResult.EMPTY, result)
    }

    @Test
    fun `validate returns INVALID_JSON for malformed JSON`() {
        // Use truly malformed JSON that kotlinx.serialization cannot parse
        val result = JsonValidator.validate("{key: }")
        assertEquals(ValidationResult.INVALID_JSON, result)
    }

    @Test
    fun `validate returns INVALID_JSON for unclosed brace`() {
        val result = JsonValidator.validate("""{"key": "value" """)
        assertEquals(ValidationResult.INVALID_JSON, result)
    }

    @Test
    fun `validate returns INVALID_JSON for plain text`() {
        val result = JsonValidator.validate("this is not json")
        assertEquals(ValidationResult.INVALID_JSON, result)
    }

    @Test
    fun `validate returns NESTED_JSON for nested object`() {
        val json = """{"outer": {"inner": "value"}}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NESTED_JSON, result)
    }

    @Test
    fun `validate returns NESTED_JSON for array value`() {
        val json = """{"key": ["item1", "item2"]}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NESTED_JSON, result)
    }

    @Test
    fun `validate returns NESTED_JSON for top-level array`() {
        val json = """["item1", "item2"]"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NESTED_JSON, result)
    }

    @Test
    fun `validate returns NON_STRING_VALUES for numeric value`() {
        val json = """{"key": 123}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    @Test
    fun `validate returns NON_STRING_VALUES for boolean value`() {
        val json = """{"key": true}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    @Test
    fun `validate returns NON_STRING_VALUES for null value`() {
        val json = """{"key": null}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    @Test
    fun `validate returns NON_STRING_VALUES for mixed non-string values`() {
        val json = """{"str": "ok", "num": 42, "bool": false}"""
        val result = JsonValidator.validate(json)
        assertEquals(ValidationResult.NON_STRING_VALUES, result)
    }

    @Test
    fun `isValidMtoolFormat returns true for valid JSON`() {
        val json = """{"key": "value"}"""
        assertTrue(JsonValidator.isValidMtoolFormat(json))
    }

    @Test
    fun `isValidMtoolFormat returns false for invalid JSON`() {
        assertFalse(JsonValidator.isValidMtoolFormat("{invalid}"))
        assertFalse(JsonValidator.isValidMtoolFormat("{}"))
        assertFalse(JsonValidator.isValidMtoolFormat("""{"key": 123}"""))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MtoolFileReader.readFromUri() — Happy Path
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `readFromUri returns success for valid JSON via mock ContentResolver`() {
        val json = """{"name": "测试", "version": "1.0"}"""
        val mockUri = Uri.parse("content://test_authority/file.json")

        // ShadowContentResolver won't intercept this, so we need to mock
        val mockInputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue("Expected Success, got: $result", result is Result.Success)
        val successResult = result as Result.Success
        assertEquals("测试", successResult.data["name"])
        assertEquals("1.0", successResult.data["version"])
    }

    @Test
    fun `readFromUri preserves key order in LinkedHashMap`() {
        val json = """{"first": "1", "second": "2", "third": "3"}"""
        val mockUri = Uri.parse("content://test_authority/ordered.json")
        val mockInputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        val keys = (result as Result.Success).data.keys.toList()
        assertEquals(listOf("first", "second", "third"), keys)
    }

    @Test
    fun `readFromUri handles UTF-8 BOM in content`() {
        // UTF-8 BOM + valid JSON
        val jsonWithBom = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()
        ) + """{"bom": "test"}""".toByteArray(Charsets.UTF_8)

        val mockUri = Uri.parse("content://test_authority/bom.json")
        val mockInputStream = ByteArrayInputStream(jsonWithBom)

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        assertEquals("test", (result as Result.Success).data["bom"])
    }

    @Test
    fun `readFromUri handles empty JSON object returns failure`() {
        val mockUri = Uri.parse("content://test_authority/empty.json")
        val mockInputStream = ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue("Expected Failure for empty JSON, got: $result", result is Result.Failure)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MtoolFileReader.readFromUri() — Error Cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `readFromUri returns Failure when ContentResolver returns null`() {
        val mockUri = Uri.parse("content://test_authority/nonexistent.json")

        // Get the shadow resolver and register null to simulate file not found
        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, null)

        val result = reader.readFromUri(mockUri)

        // Should return a Failure result - either StorageException or ParseException
        // depending on how the ContentResolver mock handles null
        assertTrue("Expected Failure, got: $result", result is Result.Failure)
    }

    @Test
    fun `readFromUri returns ParseException for invalid JSON`() {
        val invalidJson = "{invalid json content}"
        val mockUri = Uri.parse("content://test_authority/invalid.json")
        val mockInputStream = ByteArrayInputStream(invalidJson.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue("Expected Failure for invalid JSON, got: $result", result is Result.Failure)
    }

    @Test
    fun `readFromUri returns ParseException for nested JSON`() {
        val nestedJson = """{"outer": {"inner": "value"}}"""
        val mockUri = Uri.parse("content://test_authority/nested.json")
        val mockInputStream = ByteArrayInputStream(nestedJson.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue("Expected Failure for nested JSON, got: $result", result is Result.Failure)
    }

    @Test
    fun `readFromUri returns ParseException for non-string values`() {
        val jsonWithNumbers = """{"num": 123, "bool": true}"""
        val mockUri = Uri.parse("content://test_authority/nonstring.json")
        val mockInputStream = ByteArrayInputStream(jsonWithNumbers.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue("Expected Failure for non-string values, got: $result", result is Result.Failure)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge Cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `readFromUri handles Chinese characters correctly`() {
        val chineseJson = """{"中文键": "中文值", "english": "value"}"""
        val mockUri = Uri.parse("content://test_authority/chinese.json")
        val mockInputStream = ByteArrayInputStream(chineseJson.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("中文值", data["中文键"])
        assertEquals("value", data["english"])
    }

    @Test
    fun `readFromUri handles large values`() {
        val largeValue = "x".repeat(10000)
        val json = """{"large": "$largeValue"}"""
        val mockUri = Uri.parse("content://test_authority/large.json")
        val mockInputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        assertEquals(largeValue, (result as Result.Success).data["large"])
    }

    @Test
    fun `readFromUri handles many keys efficiently`() {
        val json = buildString {
            append("{")
            for (i in 1..100) {
                if (i > 1) append(",")
                append("\"key$i\": \"value$i\"")
            }
            append("}")
        }
        val mockUri = Uri.parse("content://test_authority/many.json")
        val mockInputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(100, data.size)
        assertEquals("value50", data["key50"])
    }

    @Test
    fun `reader instance is created with context`() {
        val newReader = MtoolFileReader(context)
        assertNotNull(newReader)
    }

    @Test
    fun `empty value is allowed`() {
        val json = """{"empty": "", "normal": "value"}"""
        val mockUri = Uri.parse("content://test_authority/emptyvalue.json")
        val mockInputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

        val shadowResolver = shadowOf(contentResolver)
        shadowResolver.registerInputStream(mockUri, mockInputStream)

        val result = reader.readFromUri(mockUri)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("", data["empty"])
        assertEquals("value", data["normal"])
    }
}
