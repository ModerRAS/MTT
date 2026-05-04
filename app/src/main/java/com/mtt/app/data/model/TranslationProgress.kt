package com.mtt.app.data.model

/**
 * Data class representing translation progress.
 */
data class TranslationProgress(
    val totalItems: Int,
    val completedItems: Int,
    val currentBatch: Int,
    val totalBatches: Int,
    val status: String
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