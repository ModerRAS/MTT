package com.mtt.app.domain.pipeline

/**
 * Sealed class representing the progression of a translation batch
 * through the pipeline. Emitted as a Kotlin Flow by TranslationExecutor.
 */
sealed class BatchResult {
    /** Pipeline has started processing this batch. */
    data class Started(
        val batchIndex: Int,
        val size: Int
    ) : BatchResult()

    /** In-progress: completed / total items processed in this batch. */
    data class Progress(
        val batchIndex: Int,
        val completed: Int,
        val total: Int,
        val stage: String = ""
    ) : BatchResult()

    /** Batch completed successfully. */
    data class Success(
        val batchIndex: Int,
        val items: List<String>,
        val tokensUsed: Int
    ) : BatchResult()

    /** Batch failed irrecoverably. */
    data class Failure(
        val batchIndex: Int,
        val error: Throwable
    ) : BatchResult()

    /** Validation failed; retrying with possibly smaller batch. */
    data class Retrying(
        val batchIndex: Int,
        val attempt: Int,
        val reason: String
    ) : BatchResult()
}
