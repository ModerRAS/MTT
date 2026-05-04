package com.mtt.app.data.extract

import com.mtt.app.domain.pipeline.ExtractionResult
import com.mtt.app.domain.pipeline.ResponseExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ResponseExtractor].
 *
 * Verifies parsing of LLM responses in AiNiee format, extracting numbered translation lines
 * from textarea content.
 */
class ResponseExtractorTest {

    // ═══════════════════════════════════════════════
    //  Error cases
    // ═══════════════════════════════════════════════

    @Test
    fun `empty string returns error`() {
        val result = ResponseExtractor.parse("")
        assertEquals(
            ExtractionResult.Error("Empty response", ""),
            result
        )
    }

    @Test
    fun `whitespace only returns error`() {
        val result = ResponseExtractor.parse("   \n  \t  ")
        assertEquals(
            ExtractionResult.Error("Empty response", "   \n  \t  "),
            result
        )
    }

    @Test
    fun `missing opening textarea tag returns error`() {
        val raw = "1. 译文\n2. 译文二\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Error("Missing <textarea> tag", raw),
            result
        )
    }

    @Test
    fun `missing closing textarea tag returns error`() {
        val raw = "<textarea>\n1. 译文\n2. 译文二"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Error("Missing </textarea> tag", raw),
            result
        )
    }

    // ═══════════════════════════════════════════════
    //  Success cases - empty content
    // ═══════════════════════════════════════════════

    @Test
    fun `empty textarea returns success with empty list`() {
        val result = ResponseExtractor.parse("<textarea></textarea>")
        assertEquals(
            ExtractionResult.Success(emptyList()),
            result
        )
    }

    @Test
    fun `whitespace only textarea returns success with empty list`() {
        val result = ResponseExtractor.parse("<textarea>   \n  \t  </textarea>")
        assertEquals(
            ExtractionResult.Success(emptyList()),
            result
        )
    }

    // ═══════════════════════════════════════════════
    //  Success cases - numbered lines
    // ═══════════════════════════════════════════════

    @Test
    fun `single translation with dot separator`() {
        val raw = "<textarea>1. Hello world</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("Hello world")),
            result
        )
    }

    @Test
    fun `multiple translations with dot separator`() {
        val raw = "<textarea>\n1. 译文1\n2. 译文2\n3. 译文3\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("译文1", "译文2", "译文3")),
            result
        )
    }

    @Test
    fun `translations with Chinese comma separator`() {
        val raw = "<textarea>\n1、第一个翻译\n2、第二个翻译\n3、第三个翻译\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("第一个翻译", "第二个翻译", "第三个翻译")),
            result
        )
    }

    @Test
    fun `two digit numbering strips correctly`() {
        val raw = "<textarea>\n10. 第十个\n11. 第十一个\n12. 第十二个\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("第十个", "第十一个", "第十二个")),
            result
        )
    }

    // ═══════════════════════════════════════════════
    //  Success cases - edge cases
    // ═══════════════════════════════════════════════

    @Test
    fun `unnumbered lines are kept as is`() {
        val raw = "<textarea>\nNote: these are translations\n1. First\n2. Second\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("Note: these are translations", "First", "Second")),
            result
        )
    }

    @Test
    fun `content outside textarea is ignored`() {
        val raw = "Some preamble\n<textarea>\n1. Inside\n2. Content\n</textarea>\nSome postamble"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("Inside", "Content")),
            result
        )
    }

    @Test
    fun `blank lines between translations are filtered out`() {
        val raw = "<textarea>\n1. First\n\n\n2. Second\n\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("First", "Second")),
            result
        )
    }

    @Test
    fun `mixed dot and Chinese comma separators`() {
        val raw = "<textarea>\n1. dot format\n2、中文格式\n3. another dot\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("dot format", "中文格式", "another dot")),
            result
        )
    }

    @Test
    fun `case insensitive textarea tag matching`() {
        val raw = "<TEXTAREA>\n1. UPPER\n2. CASE\n</TEXTAREA>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("UPPER", "CASE")),
            result
        )
    }

    @Test
    fun `lines with leading whitespace are trimmed`() {
        val raw = "<textarea>\n  1. Trimmed\n    2. Also trimmed\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("Trimmed", "Also trimmed")),
            result
        )
    }

    @Test
    fun `numbered line with only separator has empty content kept but stripped`() {
        // "1. " after trim is "1." which doesn't match regex (needs .+ after separator)
        // so it's kept as-is, making it ["1.", "Actually there"]
        val raw = "<textarea>\n1. \n2. Actually there\n</textarea>"
        val result = ResponseExtractor.parse(raw)
        assertEquals(
            ExtractionResult.Success(listOf("1.", "Actually there")),
            result
        )
    }
}