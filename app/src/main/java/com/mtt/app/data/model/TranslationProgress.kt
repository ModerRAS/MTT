package com.mtt.app.data.model

/**
 * Data class representing translation progress.
 *
 * @property totalInputTokens  Accumulated input tokens from LLM calls (prompt tokens).
 * @property totalOutputTokens Accumulated output tokens from LLM calls (completion tokens).
 * @property totalCacheTokens  Accumulated cached tokens (texts served from cache, estimated as input tokens).
 */
data class TranslationProgress(
    val totalItems: Int,
    val completedItems: Int,
    val currentBatch: Int,
    val totalBatches: Int,
    val status: String,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheTokens: Long = 0,
    val failedItems: List<FailedItem> = emptyList()
) {
    val percentage: Int
        get() = if (totalItems > 0) (completedItems * 100) / totalItems else 0

    companion object {
        fun initial() = TranslationProgress(
            totalItems = 0,
            completedItems = 0,
            currentBatch = 0,
            totalBatches = 0,
            status = "Idle"
        )
    }
}