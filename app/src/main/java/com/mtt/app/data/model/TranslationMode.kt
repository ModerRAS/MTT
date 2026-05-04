package com.mtt.app.data.model

/**
 * Translation mode for the app workflow:
 * - TRANSLATE: Translate untranslated items
 * - POLISH: Polish already translated items
 * - PROOFREAD: Proofread translated items
 */
enum class TranslationMode {
    TRANSLATE,
    POLISH,
    PROOFREAD
}
