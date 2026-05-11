package com.mtt.app.domain.pipeline

import com.mtt.app.data.model.FailedItem
import com.mtt.app.domain.glossary.GlossaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationVerifierTest {

    @Test
    fun `verify when translation differs from source then passes`() {
        val items = listOf(
            0 to "Hello",
            1 to "World"
        )
        val translations = mapOf(
            0 to "你好",
            1 to "世界"
        )

        val result = TranslationVerifier.verify(items, translations)

        assertEquals(2, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.passed.contains(1))
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify when translation equals source then fails`() {
        val items = listOf(
            0 to "Hello",
            1 to "World"
        )
        val translations = mapOf(
            0 to "Hello",
            1 to "World"
        )

        val result = TranslationVerifier.verify(items, translations)

        assertTrue(result.passed.isEmpty())
        assertEquals(2, result.failed.size)
        assertTrue(result.failed.any { it.globalIndex == 0 && it.sourceText == "Hello" })
        assertTrue(result.failed.any { it.globalIndex == 1 && it.sourceText == "World" })
    }

    @Test
    fun `verify when mixed pass and fail then separates correctly`() {
        val items = listOf(
            0 to "Hello",
            1 to "World",
            2 to "Test"
        )
        val translations = mapOf(
            0 to "你好",
            1 to "World",
            2 to "测试"
        )

        val result = TranslationVerifier.verify(items, translations)

        assertEquals(2, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.passed.contains(2))
        assertEquals(1, result.failed.size)
        assertEquals(1, result.failed[0].globalIndex)
        assertEquals("World", result.failed[0].sourceText)
    }

    @Test
    fun `verify when source is blank then skips`() {
        val items = listOf(
            0 to "",
            1 to "   ",
            2 to "Hello"
        )
        val translations = mapOf(
            0 to "",
            1 to "   ",
            2 to "你好"
        )

        val result = TranslationVerifier.verify(items, translations)

        // Blank items (0, 1) are skipped, Hello -> 你好 passes
        assertEquals(3, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.passed.contains(1))
        assertTrue(result.passed.contains(2))
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify when translation missing then skips`() {
        val items = listOf(
            0 to "Hello",
            1 to "World"
        )
        val translations = mapOf(
            0 to "你好"
            // 1 is missing
        )

        val result = TranslationVerifier.verify(items, translations)

        assertEquals(1, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify with whitespace differences then treats as equal`() {
        val items = listOf(
            0 to "  Hello  ",
            1 to "World"
        )
        val translations = mapOf(
            0 to "Hello",
            1 to "  World  "
        )

        val result = TranslationVerifier.verify(items, translations)

        // Whitespace is trimmed before comparison, so these are treated as equal
        assertTrue(result.passed.isEmpty())
        assertEquals(2, result.failed.size)
    }

    @Test
    fun `verify when source is pure number then skips`() {
        val items = listOf(
            0 to "123",
            1 to "45.67",
            2 to "1,000",
            3 to "Hello"
        )
        val translations = mapOf(
            0 to "123",
            1 to "45.67",
            2 to "1,000",
            3 to "你好"
        )

        val result = TranslationVerifier.verify(items, translations)

        // Pure numbers (0, 1, 2) are skipped, Hello -> 你好 passes
        assertEquals(4, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.passed.contains(1))
        assertTrue(result.passed.contains(2))
        assertTrue(result.passed.contains(3))
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify when source is pure punctuation then skips`() {
        // Test pure punctuation in isolation first
        val items = listOf(0 to "!!!", 1 to "---")
        val translations = mapOf(0 to "!!!", 1 to "---")

        val result = TranslationVerifier.verify(items, translations)

        // Both should be skipped (passed)
        assertTrue("!!! should be skipped", result.passed.contains(0))
        assertTrue("--- should be skipped", result.passed.contains(1))
        assertTrue("No failures expected", result.failed.isEmpty())
    }

    @Test
    fun `verify case sensitivity comparison`() {
        val items = listOf(
            0 to "Hello",
            1 to "World"
        )
        val translations = mapOf(
            0 to "HELLO",
            1 to "world"
        )

        val result = TranslationVerifier.verify(items, translations)

        // Case-sensitive: "Hello" != "HELLO", "World" != "world"
        assertEquals(2, result.passed.size)
        assertTrue(result.passed.contains(0))
        assertTrue(result.passed.contains(1))
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify with empty items and translations then returns empty result`() {
        val items = emptyList<Pair<Int, String>>()
        val translations = emptyMap<Int, String>()

        val result = TranslationVerifier.verify(items, translations)

        assertTrue(result.passed.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `verify fails when matched glossary target is missing`() {
        val items = listOf(
            0 to "テンタクルメイデンとアルミネス"
        )
        val translations = mapOf(
            0 to "OP设置和Polymer Dispersed Liquid Crystal确认了阿尔米涅斯"
        )
        val glossary = listOf(
            GlossaryEntry("テンタクルメイデン", "触手少女"),
            GlossaryEntry("アルミネス", "阿尔米涅斯")
        )

        val result = TranslationVerifier.verify(items, translations, glossary)

        assertTrue(result.passed.isEmpty())
        assertEquals(1, result.failed.size)
        assertEquals(0, result.failed[0].globalIndex)
    }

    @Test
    fun `verify passes when all matched glossary targets are present`() {
        val items = listOf(
            0 to "テンタクルメイデンとアルミネス"
        )
        val translations = mapOf(
            0 to "触手少女和阿尔米涅斯确认了。"
        )
        val glossary = listOf(
            GlossaryEntry("テンタクルメイデン", "触手少女"),
            GlossaryEntry("アルミネス", "阿尔米涅斯")
        )

        val result = TranslationVerifier.verify(items, translations, glossary)

        assertEquals(1, result.passed.size)
        assertTrue(result.failed.isEmpty())
    }
}
