package com.mtt.app.domain.usecase

import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.pipeline.TranslationExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Orchestrates the full translation workflow: cache lookup → LLM execution → cache write.
 *
 * For each source text, looks up [CacheManager] before invoking [TranslationExecutor].
 * Uncached texts are batched and sent to the executor.  On success, results are persisted
 * to the cache and merged with any cache hits before emission.
 */
class TranslateTextsUseCase @Inject constructor(
    private val executor: TranslationExecutor,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val TAG = "TranslateTextsUseCase"
    }

    /**
     * Translate a list of texts according to [config].
     *
     * Emits [BatchResult] progress events.  The final [BatchResult.Success] contains
     * translations for ALL input texts (cache hits + fresh translations) in the same order.
     * If a cached entry exists for every text, no LLM call is made.
     */
    operator fun invoke(
        texts: List<String>,
        config: TranslationConfig
    ): Flow<BatchResult> = flow {
        if (texts.isEmpty()) {
            emit(BatchResult.Success(batchIndex = 0, items = emptyList(), tokensUsed = 0, inputTokens = 0, outputTokens = 0, cacheTokens = 0))
            return@flow
        }

        emit(BatchResult.Started(batchIndex = 0, size = texts.size))

        // ── 1. Split into cached and uncached ──
        val modelId = config.model.modelId
        val cachedPairs = mutableListOf<Pair<Int, String>>() // (originalIndex, translatedText)
        val uncachedIndices = mutableListOf<Int>()

        for ((index, text) in texts.withIndex()) {
            val cached = cacheManager.getCached(text, config.mode, modelId)
            if (cached != null) {
                cachedPairs.add(index to cached.content)
            } else {
                uncachedIndices.add(index)
            }
        }

        if (uncachedIndices.isEmpty()) {
            val sorted = cachedPairs.sortedBy { it.first }.map { it.second }
            emit(
                BatchResult.Progress(
                    batchIndex = 0,
                    completed = texts.size,
                    total = texts.size,
                    stage = "All items cached",
                    inputTokens = 0,
                    outputTokens = 0
                )
            )
            // Estimate cache tokens for all items that were served from cache
            val cachedTokens = cachedPairs.sumOf { (_, t) ->
                com.mtt.app.data.llm.TokenEstimator.estimate(t).coerceAtLeast(1)
            }
            emit(BatchResult.Success(batchIndex = 0, items = sorted, tokensUsed = 0, inputTokens = 0, outputTokens = 0, cacheTokens = cachedTokens))
            return@flow
        }

        val uncachedTexts = uncachedIndices.map { texts[it] }
        val permanentlyFailedIndices = mutableSetOf<Int>()

        // ── 2. Execute translation for uncached texts ──
        executor.executeBatch(uncachedTexts, config).collect { result ->
            when (result) {
                is BatchResult.Started -> {
                    val completed = cachedPairs.size
                    emit(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = completed,
                            total = texts.size,
                            stage = "Translation started (${result.size} texts)",
                            inputTokens = 0,
                            outputTokens = 0
                        )
                    )
                }

                is BatchResult.Progress -> {
                    emit(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = cachedPairs.size + result.completed,
                            total = texts.size,
                            stage = result.stage,
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens
                        )
                    )
                }

                is BatchResult.Retrying -> {
                    emit(
                        BatchResult.Retrying(
                            batchIndex = 0,
                            attempt = result.attempt,
                            reason = result.reason
                        )
                    )
                }

                is BatchResult.Success -> {
                    // 3. Persist fresh translations to cache
                    uncachedIndices.zip(result.items).forEach { (originalIndex, translation) ->
                        if (originalIndex in permanentlyFailedIndices) return@forEach
                        cacheManager.saveToCache(
                            sourceText = texts[originalIndex],
                            translation = translation,
                            mode = config.mode,
                            modelId = modelId
                        )
                    }
                    // Build index → translation map
                    val translationMap = mutableMapOf<Int, String>()
                    cachedPairs.forEach { (idx, text) -> translationMap[idx] = text }
                    uncachedIndices.zip(result.items).forEach { (idx, text) -> translationMap[idx] = text }

                    val merged = (0 until texts.size).map { translationMap[it] ?: "" }

                    emit(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = texts.size,
                            total = texts.size,
                            stage = "Translation complete",
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens
                        )
                    )
                    // Estimate cache tokens for items served from use-case cache
                    val useCaseCacheTokens = cachedPairs.sumOf { (_, t) ->
                        com.mtt.app.data.llm.TokenEstimator.estimate(t).coerceAtLeast(1)
                    }
                    emit(
                        BatchResult.Success(
                            batchIndex = 0,
                            items = merged,
                            tokensUsed = result.tokensUsed,
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens,
                            cacheTokens = useCaseCacheTokens + result.cacheTokens
                        )
                    )
                }

                is BatchResult.Failure -> emit(result)

                is BatchResult.RetryComplete -> {
                    permanentlyFailedIndices.clear()
                    permanentlyFailedIndices.addAll(
                        result.finalFailedItems.mapNotNull { failed ->
                            uncachedIndices.getOrNull(failed.globalIndex)
                        }
                    )
                    AppLogger.d(TAG, "Retry complete: ${result.finalFailedItems.size} final failures")
                    emit(
                        result.copy(
                            finalFailedItems = result.finalFailedItems.map { failed ->
                                failed.copy(
                                    globalIndex = uncachedIndices.getOrNull(failed.globalIndex) ?: failed.globalIndex
                                )
                            }
                        )
                    )
                }

                is BatchResult.RetryProgress -> {
                    emit(result)
                }

                is BatchResult.VerificationComplete -> {
                    emit(
                        result.copy(
                            failedItems = result.failedItems.map { failed ->
                                failed.copy(
                                    globalIndex = uncachedIndices.getOrNull(failed.globalIndex) ?: failed.globalIndex
                                )
                            }
                        )
                    )
                }
            }
        }
    }
}
