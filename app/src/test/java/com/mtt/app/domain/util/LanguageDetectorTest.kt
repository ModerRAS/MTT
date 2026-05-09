package com.mtt.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `detect returns default when empty list`() {
        assertEquals("中文", LanguageDetector.detect(emptyList()))
    }

    @Test
    fun `detectSingle returns default when empty text`() {
        assertEquals("中文", LanguageDetector.detectSingle(""))
        assertEquals("中文", LanguageDetector.detectSingle("   "))
    }

    @Test
    fun `detectSingle detects Japanese hiragana`() {
        assertEquals("日语", LanguageDetector.detectSingle("こんにちは"))
        assertEquals("日语", LanguageDetector.detectSingle("あのイーハトーヴの"))
    }

    @Test
    fun `detectSingle detects Japanese katakana`() {
        // Use longer text with katakana
        assertEquals("日语", LanguageDetector.detectSingle("コーヒーを飲みますデータも必要です"))
        assertEquals("日语", LanguageDetector.detectSingle("データは重要です"));
    }

    @Test
    fun `detectSingle detects Chinese`() {
        assertEquals("中文", LanguageDetector.detectSingle("你好世界"))
        assertEquals("中文", LanguageDetector.detectSingle("这是中文文本"))
    }

    @Test
    fun `detectSingle detects Korean`() {
        // Use longer Korean text to ensure threshold is met
        assertEquals("韩语", LanguageDetector.detectSingle("안녕하세요 만나서 반갑습니다"))
        assertEquals("韩语", LanguageDetector.detectSingle("한국어 테스트입니다"))
    }

    @Test
    fun `detectSingle detects English`() {
        assertEquals("英语", LanguageDetector.detectSingle("Hello world"))
        assertEquals("英语", LanguageDetector.detectSingle("This is English text for testing"))
    }

    @Test
    fun `detectSingle detects German umlauts`() {
        assertEquals("德语", LanguageDetector.detectSingle("Grüß Gott und willkommen"))
    }

@Test
    fun `detectSingle detects French accents`() {
        // Use text with distinct French accent patterns
        assertEquals("法语", LanguageDetector.detectSingle("Bonjour, je suis français et je parle français"));
    }

    @Test
    fun `detectSingle detects Spanish`() {
        assertEquals("西班牙语", LanguageDetector.detectSingle("Hola, ¿cómo estás"))
    }

    @Test
    fun `detectSingle detects Russian Cyrillic`() {
        assertEquals("俄语", LanguageDetector.detectSingle("Привет мир"))
    }

    @Test
    fun `detect with multiple texts uses majority voting`() {
        val texts = listOf(
            "这是第一段中文文本",
            "こんにちは",
            "This is English text",
            "这是第二段中文文本",
            "这是第三段中文文本"
        )
        // Chinese appears 3 times, should be detected
        assertEquals("中文", LanguageDetector.detect(texts))
    }

    @Test
    fun `detect ignores short and empty texts when voting`() {
        val texts = listOf(
            "",
            "OK",
            "是",
            "こんにちは世界",
            "私は学生です"
        )
        assertEquals("日语", LanguageDetector.detect(texts))
    }

    @Test
    fun `detect treats Japanese kana and kanji mixed text as Japanese`() {
        val texts = listOf(
            "こんにちは世界",
            "私は学生です",
            "東京へ行きます"
        )
        assertEquals("日语", LanguageDetector.detect(texts))
    }

    @Test
    fun `detect with Japanese majority`() {
        val texts = listOf(
            "こんにちは",
            "ありがとうございます",
            "さようなら"
        )
        assertEquals("日语", LanguageDetector.detect(texts))
    }

    @Test
    fun `defaultTargetFor returns correct mapping`() {
        assertEquals("中文", LanguageDetector.defaultTargetFor("日语"))
        assertEquals("英语", LanguageDetector.defaultTargetFor("中文"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("韩语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("英语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("法语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("德语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("俄语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("西班牙语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("葡萄牙语"))
        assertEquals("中文", LanguageDetector.defaultTargetFor("unknown"))
    }

    @Test
    fun `detectSingle short text returns default`() {
        // Less than 5 characters
        assertEquals("中文", LanguageDetector.detectSingle("hi"))
        assertEquals("中文", LanguageDetector.detectSingle("你好"))
    }
}
