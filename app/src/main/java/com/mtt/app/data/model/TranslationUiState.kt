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

    /**
     * A previous incomplete translation job exists and can be resumed.
     * The user may choose to continue or start fresh.
     *
     * @param jobId The persisted job ID
     * @param totalItems Total items in the job
     * @param completedItems Items already translated and cached
     * @param sourceFileName Source file name for display
     */
    data class Resumable(
        val jobId: String,
        val totalItems: Int,
        val completedItems: Int,
        val sourceFileName: String?
    ) : TranslationUiState
}