package com.mtt.app.data.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [StreamingJsonReader] and round-trip write→read scenarios.
 *
 * Tests cover:
 * - Reading small JSON objects (key-value pairs)
 * - Reading large JSON objects (5000+ entries) without OOM
 * - Reading malformed JSON (truncated, invalid) — throws appropriate error
 * - Reading empty JSON object — returns 0 entries
 * - Special characters in keys/values (unicode, quotes, newlines, etc.)
 * - Reading null/boolean/numeric values
 * - Round-trip: write then read preserves data
 *
 * Uses Robolectric because [StreamingJsonReader] depends on
 * android.util.JsonReader which is Android-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamingJsonReaderTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  StreamingJsonReader — basic reading
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `readEntries reads small JSON object`() {
        val json = """{"name":"Alice","version":"1.0","locale":"en-US"}"""
        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(3, count)
            }
        }

        assertEquals("Alice", map["name"])
        assertEquals("1.0", map["version"])
        assertEquals("en-US", map["locale"])
    }

    @Test
    fun `readEntries reads large JSON object without OOM`() {
        // Generate 5000 entries — streaming reader processes one entry at a time,
        // avoiding OOM that would occur with full-tree parsers.
        val json = buildString {
            append("{")
            for (i in 1..5000) {
                if (i > 1) append(",")
                append("\"key_$i\": \"value_$i\"")
            }
            append("}")
        }

        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(5000, count)
            }
        }

        assertEquals(5000, map.size)
        assertEquals("value_1", map["key_1"])
        assertEquals("value_500", map["key_500"])
        assertEquals("value_5000", map["key_5000"])
    }

    @Test
    fun `readEntries reads empty JSON object returns zero`() {
        val json = "{}"
        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(0, count)
            }
        }

        assertTrue(map.isEmpty())
    }

    @Test
    fun `readEntries throws on truncated JSON`() {
        val truncated = """{"name":"Alice","version""" // missing closing parts
        try {
            ByteArrayInputStream(truncated.toByteArray()).use { istream ->
                StreamingJsonReader(istream).use { reader ->
                    reader.readEntries { _, _ -> }
                }
            }
            fail("Expected an exception for truncated JSON")
        } catch (e: Exception) {
            // Expected — truncated JSON is not valid
            assertTrue(true)
        }
    }

    @Test
    fun `readEntries throws on invalid JSON`() {
        val invalid = """this is not json at all"""
        try {
            ByteArrayInputStream(invalid.toByteArray()).use { istream ->
                StreamingJsonReader(istream).use { reader ->
                    reader.readEntries { _, _ -> }
                }
            }
            fail("Expected an exception for invalid JSON")
        } catch (e: Exception) {
            // Expected — not a valid JSON object
            assertTrue(true)
        }
    }

    @Test
    fun `readEntries converts non-string values per implementation behavior`() {
        // Per StreamingJsonReader source, NUMBER values are converted to string
        // (stripped of trailing zeros), BOOLEAN becomes "true"/"false",
        // and other unexpected types throw IllegalStateException.
        // ARRAY and OBJECT throw because they are not handled in the when expression.
        val json = """{"arr": [1,2]}"""
        try {
            ByteArrayInputStream(json.toByteArray()).use { istream ->
                StreamingJsonReader(istream).use { reader ->
                    reader.readEntries { _, _ -> }
                }
            }
            fail("Expected exception for nested array value")
        } catch (e: IllegalStateException) {
            // Expected — nested structures are not allowed in MTool flat format
            assertTrue(true)
        }
    }

    @Test
    fun `readEntries handles null value returns empty string`() {
        val json = """{"nullable": null, "normal": "value"}"""
        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(2, count)
            }
        }

        assertEquals("", map["nullable"])
        assertEquals("value", map["normal"])
    }

    @Test
    fun `readEntries reads boolean value as string`() {
        val json = """{"flag": true, "disabled": false}"""
        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(2, count)
            }
        }

        assertEquals("true", map["flag"])
        assertEquals("false", map["disabled"])
    }

    @Test
    fun `readEntries reads numeric value as plain string`() {
        // Numbers are read as string (trailing zeros stripped for decimals)
        val json = """{"int": 42, "decimal": 3.14000, "big": 9999999999999}"""
        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(3, count)
            }
        }

        assertEquals("42", map["int"])
        assertEquals("3.14", map["decimal"])
        assertEquals("9999999999999", map["big"])
    }

    @Test
    fun `readEntries handles special characters in keys and values`() {
        // Build JSON with special characters. Unicode escapes are decoded by Kotlin
        // at compile time; newlines/tabs use actual escape sequences in the source.
        val json = buildString {
            append("{")
            append("\"\\u4e2d\\u6587\\u952e\": \"\\u4e2d\\u6587\\u503c\",")
            append("\"multiline\": \"line1\\nline2\",")
            append("\"tabbed\": \"col1\\tcol2\"")
            append("}")
        }

        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(3, count)
            }
        }

        assertEquals("中文值", map["中文键"])
        assertEquals("line1\nline2", map["multiline"])
        assertEquals("col1\tcol2", map["tabbed"])
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Round-trip tests — write then read
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `round-trip simple map produces identical data`() {
        val original = mapOf(
            "name" to "Alice",
            "version" to "1.0",
            "locale" to "en-US"
        )

        // Write
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        // Read back
        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                reader.readEntries { k, v -> readBack[k] = v }
            }
        }

        assertEquals(original.size, readBack.size)
        for ((k, v) in original) {
            assertEquals("Value for key '$k' should match after round-trip", v, readBack[k])
        }
    }

    @Test
    fun `round-trip with unicode data`() {
        val original = mapOf(
            "chinese" to "中文测试",
            "emoji" to "👋🌍",
            "special" to "line1\nline2\ttab"
        )

        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                reader.readEntries { k, v -> readBack[k] = v }
            }
        }

        assertEquals(original.size, readBack.size)
        for ((k, v) in original) {
            assertEquals("Value for key '$k' should match", v, readBack[k])
        }
    }

    @Test
    fun `round-trip with 1000 entries preserves all data`() {
        val original = (1..1000).associate { "key_$it" to "value_$it with some text" }

        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                reader.readEntries { k, v -> readBack[k] = v }
            }
        }

        assertEquals(original.size, readBack.size)
        assertEquals("value_1 with some text", readBack["key_1"])
        assertEquals("value_500 with some text", readBack["key_500"])
        assertEquals("value_1000 with some text", readBack["key_1000"])
    }

    @Test
    fun `round-trip with empty map produces valid empty JSON`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEnd()
        }

        // writeBegin writes "{\n", writeEnd writes "\n}"
        // Result is "{\n\n}" for empty object
        val json = baos.toString()
        assertTrue("Empty JSON should start with brace", json.startsWith("{\n"))
        assertTrue("Empty JSON should end with brace", json.endsWith("\n}"))

        // Read back empty JSON
        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> readBack[k] = v }
                assertEquals(0, count)
            }
        }

        assertTrue(readBack.isEmpty())
    }

    @Test
    fun `round-trip with keys containing quotes and backslashes`() {
        val original = mapOf(
            "key\\with\\backslash" to "value\\with\\backslash",
            "key\"with\"quotes" to "value\"with\"quotes",
            "mixed\"\\key" to "mixed\"\\value"
        )

        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                reader.readEntries { k, v -> readBack[k] = v }
            }
        }

        assertEquals(original.size, readBack.size)
        for ((k, v) in original) {
            assertEquals("Value for key '$k' should match", v, readBack[k])
        }
    }

    @Test
    fun `round-trip with 500 entries preserves all data`() {
        val original = (1..500).associate { "key_$it" to "value_$it" }

        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                reader.readEntries { k, v -> readBack[k] = v }
            }
        }

        assertEquals(original.size, readBack.size)
        for ((k, v) in original) {
            assertEquals("Entry '$k' should match", v, readBack[k])
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  20 MB-scale tests — stress test with ~20 MB generated JSON payload
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `readEntries reads 420000 entries without OOM`() {
        // Generate 420 000 entries with random keys/values — produces ~20 MB
        // of raw JSON.  StreamingJsonReader must process every entry without
        // OutOfMemoryError or other failure.
        val entryCount = 420_000
        val rng = java.util.Random(42)
        val json = buildString {
            append("{")
            for (i in 1..entryCount) {
                if (i > 1) append(",")
                val key = "k${i}_${rng.nextLong()}_${rng.nextInt()}"
                val value = "v${i}_${rng.nextLong()}_${rng.nextLong()}_${rng.nextInt()}"
                append("\"$key\": \"$value\"")
            }
            append("}")
        }

        val map = mutableMapOf<String, String>()

        ByteArrayInputStream(json.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> map[k] = v }
                assertEquals(entryCount, count)
            }
        }

        assertEquals(entryCount, map.size)
        // Verify entries exist and are non-empty (spot-check)
        for (i in intArrayOf(1, 5000, 50000, 100000, 200000, 300000, 420000)) {
            val matching = map.keys.firstOrNull { it.startsWith("k${i}_") }
            assertNotNull("Entry $i should exist (key starting with k${i}_)", matching)
            val value = map[matching]
            assertNotNull("Value for entry $i should not be null", value)
            assertTrue("Value for entry $i should not be empty", value!!.isNotEmpty())
        }
    }

    @Test
    fun `round-trip with 420000 entries preserves all data`() {
        // Stress round-trip with 420 000 entries (~20 MB) — write via
        // StreamingJsonWriter, read back via StreamingJsonReader, verify
        // every single entry matches.
        val entryCount = 420_000
        val rng = java.util.Random(123)
        val original = (1..entryCount).associate {
            "k${it}_${rng.nextLong()}_${rng.nextInt()}" to
                "v${it}_${rng.nextLong()}_${rng.nextLong()}_${rng.nextInt()}"
        }

        // Write all entries
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            for ((k, v) in original) {
                writer.writeEntry(k, v)
            }
            writer.writeEnd()
        }

        // Read back
        val readBack = mutableMapOf<String, String>()
        ByteArrayInputStream(baos.toByteArray()).use { istream ->
            StreamingJsonReader(istream).use { reader ->
                val count = reader.readEntries { k, v -> readBack[k] = v }
                assertEquals(entryCount, count)
            }
        }

        // Full verification — every entry must match
        assertEquals(original.size, readBack.size)
        for ((k, v) in original) {
            assertEquals("Entry '$k' should survive round-trip", v, readBack[k])
        }
    }
}
