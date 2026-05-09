package com.mtt.app.domain.util

/**
 * Simple language detector based on Unicode character ranges.
 * Pure Kotlin — no Android dependencies.
 *
 * Supports: Japanese, Chinese, Korean, English, French, German, Russian, Spanish, Portuguese
 */
object LanguageDetector {
    private const val FALLBACK_LANGUAGE = "中文"

    /**
     * Detects the dominant language of the given texts.
     * Uses majority voting across non-trivial texts.
     *
     * @param texts List of source texts to analyze
     * @return Detected language display name (e.g. "日语", "中文"), or "中文" as fallback
     */
    fun detect(texts: List<String>): String {
        if (texts.isEmpty()) return FALLBACK_LANGUAGE

        val counts = mutableMapOf<String, Int>()
        for (text in texts) {
            val lang = detectSingleOrNull(text) ?: continue
            counts[lang] = (counts[lang] ?: 0) + 1
        }

        return counts.maxByOrNull { it.value }?.key ?: FALLBACK_LANGUAGE
    }

    /**
     * Detects the language of a single text string.
     */
    fun detectSingle(text: String): String {
        return detectSingleOrNull(text) ?: FALLBACK_LANGUAGE
    }

    private fun detectSingleOrNull(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Count characters in each language range
        var japanese = 0
        var chinese = 0
        var korean = 0
        var cyrillic = 0
        var latin = 0
        var relevantTotal = 0

        for (char in trimmed) {
            val code = char.code
            when {
                // Japanese Hiragana (3040-309F) + Katakana (30A0-30FF) in one range
                code in 0x3040..0x30FF -> {
                    japanese++
                    relevantTotal++
                }
                // Hangul Syllables (AC00-D7AF)
                code in 0xAC00..0xD7AF -> {
                    korean++
                    relevantTotal++
                }
                // CJK Unified Ideographs (4E00-9FFF), Extension A (3400-4DBF), Extension B (20000-2A6DF)
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x20000..0x2A6DF -> {
                    chinese++
                    relevantTotal++
                }
                // Cyrillic (0400-04FF)
                code in 0x0400..0x04FF -> {
                    cyrillic++
                    relevantTotal++
                }
                // Latin letters (both cases)
                (code in 0x0041..0x005A) || (code in 0x0061..0x007A) -> {
                    latin++
                    relevantTotal++
                }
                code in 0x00C0..0x024F -> {
                    latin++
                    relevantTotal++
                }
            }
        }

        // Require enough signal to avoid punctuation/short UI labels skewing file-level voting.
        if (relevantTotal < 3) return null

        // Find the dominant language (must be at least 30% of characters)
        val threshold = (relevantTotal * 0.3).toInt().coerceAtLeast(1)

        // Japanese often mixes kana with CJK; any meaningful kana signal wins for this app's text files.
        return when {
            japanese > 0 -> "日语"
            korean >= threshold -> "韩语"
            chinese >= threshold -> "中文"
            cyrillic >= threshold -> "俄语"
            latin >= threshold -> {
                // Distinguish European languages by common words (simplified)
                detectEuropeanLanguage(trimmed)
            }
            else -> "中文"
        }
    }

    /**
     * Simple heuristic for European languages.
     * Returns a best guess based on common character distributions.
     */
    private fun detectEuropeanLanguage(text: String): String {
        var latinCount = 0
        var accentCount = 0
        var total = 0

        for (char in text) {
            val code = char.code
            when {
                // French/German/Spanish/Portuguese accents (covers À-Ö, Ø-ö, ø-ÿ)
                code in 0x00C0..0x024F -> accentCount++
                // Russian Cyrillic
                code in 0x0400..0x04FF -> return "俄语"
                // Basic Latin letter (A-Z, a-z)
                code in 0x0041..0x005A || code in 0x0061..0x007A -> latinCount++
            }
            // Count all characters for threshold
            total++
        }

        // Heuristic: presence of accented chars suggests European language
        return if (accentCount > 0) {
            // Try to distinguish by common patterns
            when {
                text.contains("ß") || text.contains("ü") || text.contains("ö") || text.contains("ä") -> "德语"
                text.contains("é") || text.contains("è") || text.contains("ç") || text.contains("à") || text.contains("â") -> "法语"
                text.contains("ñ") || text.contains("¿") || text.contains("¡") -> "西班牙语"
                text.contains("ã") || text.contains("õ") || text.contains("ç") -> "葡萄牙语"
                else -> "法语"
            }
        } else {
            // Use Latin count threshold only for English vs other
            if (latinCount >= total * 0.7) {
                "英语"
            } else {
                "英语"
            }
        }
    }

    /**
     * Maps detected language to target language suggestions.
     * Returns a sensible default target for common source languages.
     */
    fun defaultTargetFor(sourceLang: String): String {
        return when (sourceLang) {
            "日语" -> "中文"
            "中文" -> "英语"
            "韩语" -> "中文"
            "英语" -> "中文"
            "法语" -> "中文"
            "德语" -> "中文"
            "俄语" -> "中文"
            "西班牙语" -> "中文"
            "葡萄牙语" -> "中文"
            else -> "中文"
        }
    }
}
