package com.mtt.app.domain.pipeline

/**
 * Patterns for text items that can safely skip LLM translation.
 *
 * These items are either purely structural (numbers, codes, identifiers)
 * and do not benefit from LLM processing. They can be passed through
 * as-is in the translation pipeline, and should also be excluded from
 * glossary extractions and prompt sections.
 */
object SkipPatterns {

    /**
     * Regex patterns identifying items that need no translation.
     *
     * Current patterns:
     * - `^\d+$`          — pure digits: "0", "123", "99999"
     * - `^EV\d+$`        — EV codes: "EV001", "ev074" (case-insensitive)
     */
    val patterns: List<Regex> = listOf(
        Regex("""^\d+$"""),
        Regex("""^EV\d+$""", RegexOption.IGNORE_CASE)
    )

    /**
     * Returns `true` if [text] matches any skip pattern.
     */
    fun shouldSkip(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.toDoubleOrNull() != null ||
            patterns.any { it.matches(trimmed) }
    }
}
