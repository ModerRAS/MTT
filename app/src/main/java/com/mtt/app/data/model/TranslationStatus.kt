package com.mtt.app.data.model

/**
 * Translation status sealed class matching AiNiee spec.
 * Codes: UNTRANSLATED=0, TRANSLATED=1, POLISHED=2, EXCLUDED=7
 */
sealed class TranslationStatus(val code: Int) {
    data object UNTRANSLATED : TranslationStatus(0)
    data object TRANSLATED : TranslationStatus(1)
    data object POLISHED : TranslationStatus(2)
    data object EXCLUDED : TranslationStatus(7)

    companion object {
        fun fromCode(code: Int): TranslationStatus = when (code) {
            0 -> UNTRANSLATED
            1 -> TRANSLATED
            2 -> POLISHED
            7 -> EXCLUDED
            else -> throw IllegalArgumentException("Unknown translation status code: $code")
        }

        fun all(): List<TranslationStatus> = listOf(
            UNTRANSLATED,
            TRANSLATED,
            POLISHED,
            EXCLUDED
        )
    }
}