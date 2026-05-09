package com.mtt.app.domain.pipeline

import com.mtt.app.data.model.FailedItem

/**
 * Result of verification: lists passed and failed items.
 */
data class VerificationResult(
    /** Global indices that passed verification. */
    val passed: List<Int>,
    /** Failed items with retry info. */
    val failed: List<FailedItem>
)

/**
 * Verifies translation results, detecting when LLM failed to translate
 * (source text was returned unchanged).
 */
object TranslationVerifier {

    /**
     * Verify translations against source texts.
     *
     * @param items List of (globalIndex, sourceText) pairs
     * @param translations Map of globalIndex -> translatedText
     * @return VerificationResult with passed/failed indices
     */
    fun verify(
        items: List<Pair<Int, String>>,
        translations: Map<Int, String>
    ): VerificationResult {
        val passed = mutableListOf<Int>()
        val failed = mutableListOf<FailedItem>()

        for ((globalIndex, source) in items) {
            val translated = translations[globalIndex] ?: continue

            if (shouldSkip(source)) {
                // Non-translatable content — always pass
                passed.add(globalIndex)
                continue
            }

            val trimmedSource = source.trim()
            val trimmedTranslation = translated.trim()

            if (trimmedSource == trimmedTranslation) {
                // LLM failed to translate — source returned unchanged
                failed.add(
                    FailedItem(
                        globalIndex = globalIndex,
                        sourceText = trimmedSource
                    )
                )
            } else {
                passed.add(globalIndex)
            }
        }

        return VerificationResult(
            passed = passed,
            failed = failed
        )
    }

    /**
     * Checks if source text should be skipped from verification.
     *
     * Skip rules:
     * - Blank or empty strings
     * - Pure numbers (with at least one digit)
     * - Pure punctuation
     */
    private fun shouldSkip(source: String): Boolean {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return true

        // Check if pure numbers (digits, decimal points, commas, minus sign for negatives)
        val isPureNumberChars = trimmed.all { it.isDigit() || it == '.' || it == ',' || it == '-' }
        if (isPureNumberChars && trimmed.any { it.isDigit() }) {
            // Skip only if it's a valid number (has at least one digit)
            return true
        }

        // Check if pure punctuation (no letters, digits, or whitespace)
        if (trimmed.all { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            return true
        }

        return false
    }
}
