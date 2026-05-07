package com.mtt.app.domain.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SkipPatterns] — items that need no LLM translation.
 */
class SkipPatternsTest {

    // ── Pure digits ──────────────────────────────

    @Test
    fun `shouldSkip single digit returns true`() {
        assertTrue(SkipPatterns.shouldSkip("0"))
        assertTrue(SkipPatterns.shouldSkip("1"))
        assertTrue(SkipPatterns.shouldSkip("5"))
        assertTrue(SkipPatterns.shouldSkip("9"))
    }

    @Test
    fun `shouldSkip multi digit returns true`() {
        assertTrue(SkipPatterns.shouldSkip("123"))
        assertTrue(SkipPatterns.shouldSkip("99999"))
        assertTrue(SkipPatterns.shouldSkip("1000"))
        assertTrue(SkipPatterns.shouldSkip("42027"))
    }

    @Test
    fun `shouldSkip negative number returns true`() {
        assertTrue(SkipPatterns.shouldSkip("-1"))
        assertTrue(SkipPatterns.shouldSkip("-500"))
    }

    @Test
    fun `shouldSkip decimal number returns true`() {
        assertTrue(SkipPatterns.shouldSkip("3.14"))
        assertTrue(SkipPatterns.shouldSkip("0.5"))
        assertTrue(SkipPatterns.shouldSkip("100.0"))
    }

    // ── EV codes ─────────────────────────────────

    @Test
    fun `shouldSkip EV followed by digits returns true`() {
        assertTrue(SkipPatterns.shouldSkip("EV001"))
        assertTrue(SkipPatterns.shouldSkip("EV074"))
        assertTrue(SkipPatterns.shouldSkip("EV999"))
        assertTrue(SkipPatterns.shouldSkip("EV100500"))
    }

    @Test
    fun `shouldSkip lowercase ev followed by digits returns true`() {
        assertTrue(SkipPatterns.shouldSkip("ev001"))
        assertTrue(SkipPatterns.shouldSkip("ev074"))
        assertTrue(SkipPatterns.shouldSkip("ev999"))
    }

    @Test
    fun `shouldSkip mixed case EV followed by digits returns true`() {
        assertTrue(SkipPatterns.shouldSkip("Ev001"))
        assertTrue(SkipPatterns.shouldSkip("eV074"))
    }

    // ── Text that should NOT be skipped ──────────

    @Test
    fun `shouldSkip Japanese text returns false`() {
        assertFalse(SkipPatterns.shouldSkip("難易度設定"))
        assertFalse(SkipPatterns.shouldSkip("触手66"))
        assertFalse(SkipPatterns.shouldSkip("うぉっしゃ7"))
        assertFalse(SkipPatterns.shouldSkip("罠2-5-3t"))
    }

    @Test
    fun `shouldSkip Chinese text returns false`() {
        assertFalse(SkipPatterns.shouldSkip("难度设置"))
        assertFalse(SkipPatterns.shouldSkip("怪物2左"))
        assertFalse(SkipPatterns.shouldSkip("胜利台词"))
    }

    @Test
    fun `shouldSkip English text returns false`() {
        assertFalse(SkipPatterns.shouldSkip("Hello"))
        assertFalse(SkipPatterns.shouldSkip("Monster 2 Left"))
        assertFalse(SkipPatterns.shouldSkip("Attacked it"))
    }

    @Test
    fun `shouldSkip text with leading number but no separator returns false`() {
        // These start with digits but aren't pure numbers or EV codes
        assertFalse(SkipPatterns.shouldSkip("1st Boss"))
        assertFalse(SkipPatterns.shouldSkip("2nd Chance"))
        assertFalse(SkipPatterns.shouldSkip("3way Battle"))
    }

    @Test
    fun `shouldSkip text with number prefix and separator returns false`() {
        // Source text that starts with a number prefix like "102. 難易度設定"
        // should NOT be skipped — the prefix is part of the source
        assertFalse(SkipPatterns.shouldSkip("102. 難易度設定"))
        assertFalse(SkipPatterns.shouldSkip("1. EV001"))
        assertFalse(SkipPatterns.shouldSkip("100. ステージ"))
    }

    @Test
    fun `shouldSkip EV not followed by digits returns false`() {
        assertFalse(SkipPatterns.shouldSkip("EVENT"))
        assertFalse(SkipPatterns.shouldSkip("EVERY"))
        assertFalse(SkipPatterns.shouldSkip("evil"))
        assertFalse(SkipPatterns.shouldSkip("even"))
    }

    @Test
    fun `shouldSkip EV with extra characters returns false`() {
        assertFalse(SkipPatterns.shouldSkip("EV001A"))
        assertFalse(SkipPatterns.shouldSkip("EV-001"))
        assertFalse(SkipPatterns.shouldSkip("EV_001"))
    }

    @Test
    fun `shouldSkip empty string returns false`() {
        assertFalse(SkipPatterns.shouldSkip(""))
    }

    @Test
    fun `shouldSkip whitespace only returns false`() {
        assertFalse(SkipPatterns.shouldSkip("   "))
        assertFalse(SkipPatterns.shouldSkip("\t"))
    }

    @Test
    fun `shouldSkip mixed content with numbers returns false`() {
        assertFalse(SkipPatterns.shouldSkip("abc123"))
        assertFalse(SkipPatterns.shouldSkip("123abc"))
        assertFalse(SkipPatterns.shouldSkip("abcEV001"))
    }

    @Test
    fun `shouldSkip text with leading zeros returns true`() {
        assertTrue(SkipPatterns.shouldSkip("000"))
        assertTrue(SkipPatterns.shouldSkip("00123"))
    }

    // ── Edge cases ───────────────────────────────

    @Test
    fun `shouldSkip special characters only returns false`() {
        assertFalse(SkipPatterns.shouldSkip("@#$%"))
        assertFalse(SkipPatterns.shouldSkip("_test"))
    }

    @Test
    fun `shouldSkip single letters returns false`() {
        assertFalse(SkipPatterns.shouldSkip("a"))
        assertFalse(SkipPatterns.shouldSkip("Z"))
        assertFalse(SkipPatterns.shouldSkip("啊"))
    }

    @Test
    fun `shouldSkip EV with leading space returns true`() {
        // Trailing/leading whitespace is trimmed before checking
        assertTrue(SkipPatterns.shouldSkip("  EV074  "))
        assertTrue(SkipPatterns.shouldSkip("\tEV001\n"))
    }
}
