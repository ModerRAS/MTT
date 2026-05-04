package com.mtt.app.domain.usecase

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
            emit(BatchResult.Success(batchIndex = 0, items = emptyList(), tokensUsed = 0))
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
                    stage = "All items cached"
                )
            )
            emit(BatchResult.Success(batchIndex = 0, items = sorted, tokensUsed = 0))
            return@flow
        }

        val uncachedTexts = uncachedIndices.map { texts[it] }

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
                            stage = "Translation started (${result.size} texts)"
                        )
                    )
                }

                is BatchResult.Progress -> {
                    emit(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = cachedPairs.size + result.completed,
                            total = texts.size,
                            stage = result.stage
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
                            stage = "Translation complete"
                        )
                    )
                    emit(
                        BatchResult.Success(
                            batchIndex = 0,
                            items = merged,
                            tokensUsed = result.tokensUsed
                        )
                    )
                }

                is BatchResult.Failure -> emit(result)
            }
        }
    }
}
