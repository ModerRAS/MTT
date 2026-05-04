package com.mtt.app.data.llm

import com.mtt.app.data.model.ModelInfo

/**
 * Calculator for determining optimal batch sizes for LLM requests.
 */
object BatchCalculator {

    private const val DEFAULT_INITIAL_BATCH_SIZE = 50
    private const val MIN_BATCH_SIZE = 1
    private const val USER_PROMPT_PREFIX = ""

    /**
     * Calculate maximum batch size that fits within model's context window.
     * Uses greedy approach with halving strategy.
     *
     * @param texts List of texts to potentially batch
     * @param model Model information with context window
     * @param systemPrompt System prompt to include
     * @return Maximum number of items that fit in context
     */
    fun calculateMaxBatchSize(
        texts: List<String>,
        model: ModelInfo,
        systemPrompt: String
    ): Int {
        if (texts.isEmpty()) return 0

        var batchSize = calculateInitialBatchSize(model)
        var currentTexts = texts

        while (batchSize >= MIN_BATCH_SIZE) {
            // Check if current batch size fits
            while (currentTexts.isNotEmpty()) {
                val batchToCheck = currentTexts.take(batchSize)
                val totalTokens = TokenEstimator.estimateBatch(
                    batchToCheck,
                    systemPrompt,
                    USER_PROMPT_PREFIX
                )

                if (TokenEstimator.canFitInContext(totalTokens, model)) {
                    return batchToCheck.size
                }
            }

            // If full list doesn't fit even batchSize=1, return 0
            if (batchSize == MIN_BATCH_SIZE) {
                val totalTokens = TokenEstimator.estimateBatch(
                    texts.take(MIN_BATCH_SIZE),
                    systemPrompt,
                    USER_PROMPT_PREFIX
                )
                return if (TokenEstimator.canFitInContext(totalTokens, model)) 1 else 0
            }

            // Halve the batch size and retry
            batchSize /= 2
            currentTexts = texts
        }

        return 0
    }

    /**
     * Calculate initial batch size for a model.
     * Most models start with batch size of 50.
     *
     * @param model Model information
     * @return Initial batch size (default 50)
     */
    fun calculateInitialBatchSize(model: ModelInfo): Int {
        return DEFAULT_INITIAL_BATCH_SIZE
    }

    /**
     * Calculate reduced batch size for retry after context overflow.
     *
     * @param originalBatchSize Original batch size that failed
     * @return Reduced batch size (original / 2, minimum 1)
     */
    fun calculateRetryBatchSize(originalBatchSize: Int): Int {
        return maxOf(originalBatchSize / 2, MIN_BATCH_SIZE)
    }
}