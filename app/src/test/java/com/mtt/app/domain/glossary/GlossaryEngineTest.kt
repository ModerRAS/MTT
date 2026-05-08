package com.mtt.app.domain.glossary

import com.mtt.app.data.model.TranslationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GlossaryEngine].
 */
class GlossaryEngineTest {

    // ═══════════════════════════════════════════════════════════════
    //  match() tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `match returns empty list for empty text`() {
        val glossary = listOf(GlossaryEntry("HP", "生命值"))
        val result = GlossaryEngine.match("", glossary)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `match returns empty list for empty glossary`() {
        val result = GlossaryEngine.match("HP is important", emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `match finds basic literal match`() {
        val glossary = listOf(
            GlossaryEntry("HP", "Health"),
            GlossaryEntry("MP", "Magic")
        )
        val result = GlossaryEngine.match("Your HP is full", glossary)

        assertEquals(1, result.size)
        assertEquals("HP", result[0].matchedText)
        assertEquals(5, result[0].startIndex)
        assertEquals(7, result[0].endIndex)
        assertEquals("Health", result[0].entry.target)
    }

    @Test
    fun `match finds multiple matches`() {
        val glossary = listOf(
            GlossaryEntry("HP", "Health", isCaseSensitive = true),
            GlossaryEntry("MP", "Magic", isCaseSensitive = true)
        )
        val result = GlossaryEngine.match("HP and MP are important stats", glossary)

        assertEquals(2, result.size)
        assertEquals("HP", result[0].matchedText)
        assertEquals("MP", result[1].matchedText)
    }

    @Test
    fun `match handles case insensitive matching`() {
        val glossary = listOf(
            GlossaryEntry("hp", "Health", isCaseSensitive = false)
        )
        val result = GlossaryEngine.match("HP and hp and Hp", glossary)

        assertEquals(3, result.size)
    }

    @Test
    fun `match handles regex patterns`() {
        val glossary = listOf(
            GlossaryEntry("\\d+HP", "生命值", isRegex = true)
        )
        val result = GlossaryEngine.match("100HP is the max", glossary)

        assertEquals(1, result.size)
        assertEquals("100HP", result[0].matchedText)
    }

    @Test
    fun `match gives priority to longer matches`() {
        val glossary = listOf(
            GlossaryEntry("HP", "生命值"),
            GlossaryEntry("HP Regen", "生命恢复")
        )
        val result = GlossaryEngine.match("HP Regen is good", glossary)

        assertEquals(1, result.size)
        assertEquals("HP Regen", result[0].matchedText)
        assertEquals("生命恢复", result[0].entry.target)
    }

    @Test
    fun `match skips invalid regex patterns`() {
        val glossary = listOf(
            GlossaryEntry("[invalid", "test"), // invalid regex
            GlossaryEntry("HP", "生命值")
        )
        val result = GlossaryEngine.match("HP is here", glossary)

        assertEquals(1, result.size)
        assertEquals("HP", result[0].matchedText)
    }

    // ═══════════════════════════════════════════════════════════════
    //  isProhibited() tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `isProhibited returns true when prohibited term found`() {
        val prohibited = listOf("ProhibitedTerm", "DoNotTranslate")
        val result = GlossaryEngine.isProhibited("This contains ProhibitedTerm", prohibited)
        assertTrue(result)
    }

    @Test
    fun `isProhibited returns false when no prohibited term`() {
        val prohibited = listOf("ProhibitedTerm", "DoNotTranslate")
        val result = GlossaryEngine.isProhibited("This is clean text", prohibited)
        assertFalse(result)
    }

    @Test
    fun `isProhibited returns false for empty prohibition list`() {
        val result = GlossaryEngine.isProhibited("Any text", emptyList<String>())
        assertFalse(result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  protect() tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `protect replaces terms with placeholders`() {
        val glossary = listOf(GlossaryEntry("HP", "Health"))
        val result = GlossaryEngine.protect("Your HP is full", glossary)

        assertEquals("Your {GLO_0} is full", result.protectedText)
        assertEquals(1, result.placeholders.size)
        assertEquals("Health", result.placeholders["{GLO_0}"])
    }

    @Test
    fun `protect returns original text when no matches`() {
        val glossary = listOf(GlossaryEntry("HP", "Health"))
        val result = GlossaryEngine.protect("No matches here", glossary)

        assertEquals("No matches here", result.protectedText)
        assertTrue(result.placeholders.isEmpty())
    }

    @Test
    fun `protect handles multiple terms`() {
        val glossary = listOf(
            GlossaryEntry("HP", "Health"),
            GlossaryEntry("MP", "Magic")
        )
        val result = GlossaryEngine.protect("HP and MP are stats", glossary)

        assertEquals("{GLO_0} and {GLO_1} are stats", result.protectedText)
        assertEquals("Health", result.placeholders["{GLO_0}"])
        assertEquals("Magic", result.placeholders["{GLO_1}"])
    }

    // ═══════════════════════════════════════════════
    //  restore() tests
    // ═══════════════════════════════════════════════

    @Test
    fun `restore replaces placeholders with target terms`() {
        val placeholders = mapOf("{GLO_0}" to "Health")
        val result = GlossaryEngine.restore("Your {GLO_0} is full", placeholders)

        assertEquals("Your Health is full", result)
    }

    @Test
    fun `restore returns original text for empty placeholders`() {
        val result = GlossaryEngine.restore("No placeholders here", emptyMap())
        assertEquals("No placeholders here", result)
    }

    @Test
    fun `restore handles multiple placeholders`() {
        val placeholders = mapOf(
            "{GLO_0}" to "Health",
            "{GLO_1}" to "Magic"
        )
        val result = GlossaryEngine.restore("{GLO_0} and {GLO_1}", placeholders)

        assertEquals("Health and Magic", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  buildGlossarySection() tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `buildGlossarySection builds correct format`() {
        val entries = listOf(
            GlossaryEntry("HP", "Health"),
            GlossaryEntry("MP", "Magic")
        )
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.TRANSLATE)

        assertTrue(result.contains("###术语表"))
        assertTrue(result.contains("原文|译文|备注"))
        assertTrue(result.contains("HP|Health|"))
        assertTrue(result.contains("MP|Magic|"))
    }

    @Test
    fun `buildGlossarySection returns empty for empty entries`() {
        val result = GlossaryEngine.buildGlossarySection(emptyList(), TranslationMode.TRANSLATE)
        assertEquals("", result)
    }

    @Test
    fun `buildGlossarySection includes mode parameter but returns section regardless of mode`() {
        // Note: Current implementation ignores mode parameter
        val entries = listOf(GlossaryEntry("HP", "Health"))
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.PROOFREAD)
        assertTrue(result.contains("HP|Health|"))
    }

    @Test
    fun `buildGlossarySection filters out pure number entries`() {
        val entries = listOf(
            GlossaryEntry("0", "Zero"),
            GlossaryEntry("123", "OneTwoThree"),
            GlossaryEntry("HP", "Health")
        )
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.TRANSLATE)
        assertTrue("Should contain normal entry", result.contains("HP|Health|"))
        assertFalse("Should NOT contain '0' entry", result.contains("0|Zero|"))
        assertFalse("Should NOT contain '123' entry", result.contains("123|OneTwoThree|"))
    }

    @Test
    fun `buildGlossarySection filters out EV code entries`() {
        val entries = listOf(
            GlossaryEntry("EV001", "EV001"),
            GlossaryEntry("ev074", "ev074"),
            GlossaryEntry("MP", "Magic")
        )
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.TRANSLATE)
        assertTrue("Should contain normal entry", result.contains("MP|Magic|"))
        assertFalse("Should NOT contain EV001", result.contains("EV001"))
        assertFalse("Should NOT contain ev074", result.contains("ev074"))
    }

    @Test
    fun `buildGlossarySection returns empty when all entries are skipped`() {
        val entries = listOf(
            GlossaryEntry("0", "Zero"),
            GlossaryEntry("1", "One"),
            GlossaryEntry("EV999", "EV999")
        )
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.TRANSLATE)
        assertEquals("All entries are skip patterns, should be empty", "", result)
    }

    @Test
    fun `buildGlossarySection keeps entries that start with number prefix`() {
        // Entries that start with number prefix but aren't pure numbers should be kept
        val entries = listOf(
            GlossaryEntry("102. Difficulty", "DifficultySetting")
        )
        val result = GlossaryEngine.buildGlossarySection(entries, TranslationMode.TRANSLATE)
        assertTrue("Entry with source number prefix should be kept", result.contains("102. Difficulty"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  buildProhibitionSection() tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `buildProhibitionSection builds correct format`() {
        val entries = listOf(
            GlossaryEntry("Term1", "do not translate"),
            GlossaryEntry("Term2", "keep as is")
        )
        val result = GlossaryEngine.buildProhibitionSection(entries)

        assertTrue(result.contains("###DoNotTranslate"))
        assertTrue(result.contains("Term1|do not translate"))
        assertTrue(result.contains("Term2|keep as is"))
    }

    @Test
    fun `buildProhibitionSection returns empty for empty entries`() {
        val result = GlossaryEngine.buildProhibitionSection(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `buildProhibitionSection filters out pure number entries`() {
        val entries = listOf(
            GlossaryEntry("0", "keep"),
            GlossaryEntry("999", "keep"),
            GlossaryEntry("Term1", "do not translate")
        )
        val result = GlossaryEngine.buildProhibitionSection(entries)
        assertTrue("Should contain normal entry", result.contains("Term1|do not translate"))
        assertFalse("Should NOT contain '0' entry", result.contains("0|keep"))
    }

    @Test
    fun `buildProhibitionSection filters out EV code entries`() {
        val entries = listOf(
            GlossaryEntry("EV001", "EV001"),
            GlossaryEntry("Term1", "do not translate")
        )
        val result = GlossaryEngine.buildProhibitionSection(entries)
        assertTrue("Should contain normal entry", result.contains("Term1"))
        assertFalse("Should NOT contain EV001", result.contains("EV001"))
    }

    @Test
    fun `buildProhibitionSection returns empty when all entries are skipped`() {
        val entries = listOf(
            GlossaryEntry("0", "zero"),
            GlossaryEntry("EV074", "EV074")
        )
        val result = GlossaryEngine.buildProhibitionSection(entries)
        assertEquals("All entries are skip patterns, should be empty", "", result)
    }
}
