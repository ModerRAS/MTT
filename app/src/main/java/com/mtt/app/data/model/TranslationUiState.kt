package com.mtt.app.data.model

/**
 * Sealed interface for translation UI states.
 */
sealed interface TranslationUiState {
    data object Idle : TranslationUiState

    data class Loading(val message: String) : TranslationUiState

    data class Translating(val progress: TranslationProgress) : TranslationUiState

    data object Completed : TranslationUiState

    data class Error(val message: String) : TranslationUiState
}