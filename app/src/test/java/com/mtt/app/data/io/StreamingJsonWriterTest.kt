package com.mtt.app.data.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [StreamingJsonWriter].
 *
 * Tests cover:
 * - Writing JSON objects
 * - Writing preserves many key-value pairs
 * - Escaping special characters (backslash, quotes, newlines, tabs, etc.)
 * - Unicode characters (Chinese, emoji)
 * - Empty string values
 * - Idempotent close
 *
 * These are pure JVM tests — no Android dependencies.
 */
class StreamingJsonWriterTest {

    @Test
    fun `writeEntry produces valid JSON structure`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("name", "Alice")
            writer.writeEntry("version", "1.0")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("JSON should start with opening brace + newline", json.startsWith("{\n"))
        assertTrue("JSON should end with newline + closing brace", json.endsWith("\n}"))
        assertTrue("JSON should contain escaped key 'name'", json.contains("\"name\""))
        assertTrue("JSON should contain escaped value 'Alice'", json.contains("\"Alice\""))
        assertTrue("JSON should contain 'version'", json.contains("\"version\""))
        assertTrue("JSON should contain '1.0'", json.contains("\"1.0\""))
    }

    @Test
    fun `writeEntry escapes backslash`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("path", "C:\\Users\\test")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Backslash should be escaped as \\\\",
            json.contains("C:\\\\Users\\\\test") || json.contains("\\\\U"))
    }

    @Test
    fun `writeEntry escapes double quotes`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("quote", "say \"hello\"")
            writer.writeEnd()
        }

        val json = baos.toString()
        // The escaped value should contain \"
        assertTrue("Quote should be escaped as \\\"", json.contains("\\\""))
    }

    @Test
    fun `writeEntry escapes newline`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("multiline", "line1\nline2")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Newline should be escaped as \\n", json.contains("\\n"))
    }

    @Test
    fun `writeEntry escapes tab`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("tabbed", "col1\tcol2")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Tab should be escaped as \\t", json.contains("\\t"))
    }

    @Test
    fun `writeEntry escapes carriage return`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("crlf", "line1\r\nline2")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("CR should be escaped as \\r", json.contains("\\r"))
    }

    @Test
    fun `writeEntry escapes form feed`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("formfeed", "page1\u000Cpage2")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Form feed should be escaped as \\f", json.contains("\\f"))
    }

    @Test
    fun `writeEntry escapes backspace`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("backspace", "back\bspace")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Backspace should be escaped as \\b", json.contains("\\b"))
    }

    @Test
    fun `writeEntry handles unicode Chinese characters`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("中文键", "中文值")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("JSON should contain Chinese key", json.contains("中文键"))
        assertTrue("JSON should contain Chinese value", json.contains("中文值"))
    }

    @Test
    fun `writeEntry handles emoji`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("emoji", "Hello 👋 World 🌍")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("JSON should contain emoji", json.contains("👋"))
    }

    @Test
    fun `writeEntry handles empty string value`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("emptyValue", "")
            writer.writeEntry("normalValue", "test")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("JSON should contain empty value pair", json.contains("\"emptyValue\": \"\""))
        assertTrue("JSON should contain normal value", json.contains("\"normalValue\": \"test\""))
    }

    @Test
    fun `writeEntry adds comma between multiple entries`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("key1", "value1")
            writer.writeEntry("key2", "value2")
            writer.writeEntry("key3", "value3")
            writer.writeEnd()
        }

        val json = baos.toString()
        // Check that entries are separated by comma+newline
        assertTrue("Entries should be separated by comma+newline", json.contains(",\n"))
    }

    @Test
    fun `writeEntry uses 2-space indent`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("key", "value")
            writer.writeEnd()
        }

        val json = baos.toString()
        assertTrue("Entries should be indented with 2 spaces", json.contains("  \"key\""))
    }

    @Test
    fun `close is idempotent`() {
        val baos = ByteArrayOutputStream()
        val writer = StreamingJsonWriter(baos)
        writer.writeBegin()
        writer.writeEntry("key", "value")
        writer.writeEnd()
        writer.close()
        writer.close() // Second close must not throw
    }

    @Test
    fun `close calls writeEnd if not already closed`() {
        val baos = ByteArrayOutputStream()
        val writer = StreamingJsonWriter(baos)
        writer.writeBegin()
        writer.writeEntry("key", "value")
        writer.close() // Should implicitly call writeEnd
        // Output should still be valid JSON
        val json = baos.toString()
        assertTrue("JSON should have closing brace after close()", json.contains("}"))
    }

    @Test
    fun `writeEnd flushes output`() {
        val baos = ByteArrayOutputStream()
        StreamingJsonWriter(baos).use { writer ->
            writer.writeBegin()
            writer.writeEntry("key", "value")
            writer.writeEnd()
        }
        // After use() block, data should be flushed
        val json = baos.toString()
        assertTrue(json.isNotEmpty())
    }
}
