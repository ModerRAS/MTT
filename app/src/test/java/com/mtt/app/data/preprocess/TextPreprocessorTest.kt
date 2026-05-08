package com.mtt.app.data.preprocess

import com.mtt.app.domain.pipeline.TextPreprocessor
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [TextPreprocessor].
 *
 * Tests cover: empty text handling, line ending normalization,
 * {X-…} tag extraction/restoration, LLM safety escaping,
 * prefix/suffix whitespace preservation, and round-trip post-processing.
 */
class TextPreprocessorTest {

    // ── preprocess() tests ─────────────────────────────────

    @Test
    fun `preprocess handles empty text`() {
        val result = TextPreprocessor.preprocess("")

        assertTrue(result.segments.size == 1)
        assertTrue(result.segments[0].isEmpty)
        assertEquals("", result.segments[0].text)
    }

    @Test
    fun `preprocess keeps entire text as one segment`() {
        val text = "Hello\nWorld\nTest"
        val result = TextPreprocessor.preprocess(text)

        assertEquals(1, result.segments.size)
        assertEquals("Hello\nWorld\nTest", result.segments[0].text)
    }

    @Test
    fun `preprocess normalizes line endings but keeps as single segment`() {
        val text = "Line1\r\nLine2\rLine3\nLine4"
        val result = TextPreprocessor.preprocess(text)

        assertEquals(1, result.segments.size)
        assertTrue(result.segments[0].text.contains("\n"))
        assertTrue(result.metadata.lineEndings.isNotEmpty())
    }

    @Test
    fun `preprocess extracts X-tags and replaces with placeholders`() {
        val text = "Hello {X-NAME} World"
        val result = TextPreprocessor.preprocess(text)

        assertTrue(result.metadata.hasTags)
        assertFalse(result.segments[0].text.contains("{X-NAME}"))
        assertTrue(result.segments[0].text.contains("\\[T0\\]"))
    }

    @Test
    fun `preprocess escapes LLM-sensitive characters`() {
        val text = "Test `backticks` #hash [brackets] <tags>"
        val result = TextPreprocessor.preprocess(text)

        val segmentText = result.segments[0].text
        assertTrue(segmentText.contains("\\`"))
        assertTrue(segmentText.contains("\\#"))
        assertTrue(segmentText.contains("\\["))
        assertTrue(segmentText.contains("\\]"))
    }

    @Test
    fun `preprocess preserves leading whitespace as prefix`() {
        val text = "    Indented line"
        val result = TextPreprocessor.preprocess(text)

        assertEquals("    ", result.segments[0].prefix)
        assertEquals("Indented line", result.segments[0].text)
    }

    @Test
    fun `preprocess preserves trailing whitespace as suffix`() {
        val text = "Trailing spaces    "
        val result = TextPreprocessor.preprocess(text)

        assertEquals("    ", result.segments[0].suffix)
    }

    @Test
    fun `preprocess marks non-empty text correctly`() {
        val text = "Line1\n\nLine3"
        val result = TextPreprocessor.preprocess(text)

        assertEquals(1, result.segments.size)
        assertFalse(result.segments[0].isEmpty)
        assertEquals("Line1\n\nLine3", result.segments[0].text)
    }

    // ── postprocess() tests ─────────────────────────────────

    @Test
    fun `postprocess restores original line endings`() {
        val text = "Line1\r\nLine2"
        val preprocessed = TextPreprocessor.preprocess(text)
        val translated = listOf("Translated1\nTranslated2")
        val result = TextPreprocessor.postprocess(translated, preprocessed.metadata)

        assertTrue(result.contains("\n"))
    }

    @Test
    fun `postprocess restores X-tags from placeholders`() {
        val text = "Hello {X-NAME} World"
        val preprocessed = TextPreprocessor.preprocess(text)
        val translated = listOf("Bonjour [T0] Monde")
        val result = TextPreprocessor.postprocess(translated, preprocessed.metadata)

        assertTrue(result.contains("{X-NAME}"))
        assertFalse(result.contains("[T0]"))
    }

    @Test
    fun `postprocess unescapes LLM-safe characters`() {
        val text = "Test `code`"
        val preprocessed = TextPreprocessor.preprocess(text)
        val translated = listOf("Test \\`code\\`")
        val result = TextPreprocessor.postprocess(translated, preprocessed.metadata)

        assertTrue(result.contains("`"))
        assertFalse(result.contains("\\`"))
    }

    // ── Round-trip integration tests ───────────────────────

    @Test
    fun `full round-trip preserves content structure`() {
        val original = "First line\nSecond line\n\nLast line"
        val preprocessed = TextPreprocessor.preprocess(original)

        // Simulate translation (identity for testing)
        val translated = preprocessed.segments.map { seg ->
            if (seg.isEmpty) "" else seg.text
        }

        val result = TextPreprocessor.postprocess(translated, preprocessed.metadata)

        assertFalse(result.contains("[T"))
        assertFalse(result.contains("\\`"))
    }
}