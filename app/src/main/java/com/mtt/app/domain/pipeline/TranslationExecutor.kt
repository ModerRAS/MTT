package com.mtt.app.domain.pipeline

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.TranslationException
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.llm.RateLimitException
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.llm.TokenEstimator
import com.mtt.app.data.model.FailedItem
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.remote.anthropic.AnthropicClient
import com.mtt.app.data.remote.llm.LlmService
import com.mtt.app.data.remote.llm.LlmServiceFactory
import com.mtt.app.data.remote.openai.OpenAiClient
import com.mtt.app.domain.glossary.GlossaryEngine
import com.mtt.app.domain.glossary.GlossaryEntry
import com.mtt.app.domain.glossary.ProtectResult
import com.mtt.app.domain.prompt.PromptBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full translation pipeline:
 * preprocess → build prompt → estimate tokens → rate limit → LLM call → validate → extract.
 *
 * Chunks texts by [TranslationConfig.batchSize] and processes them with
 * [TranslationConfig.concurrency] parallelism. Emits progress via [BatchResult] flow.
 *
 * @param llmService   Provider-specific LLM API client
 * @param rateLimiter  RPM/TPM rate limiter for API quota management
 */
@Singleton
class TranslationExecutor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val rateLimiter: RateLimiter,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val TAG = "TranslationExecutor"
    }

    // ──────────────────────────────────────────────
    //  LLM service management
    // ──────────────────────────────────────────────

    /**
     * The LlmService is created dynamically from [TranslationConfig.model.provider]
     * at the start of each [executeBatch] call, since the provider (OpenAI / Anthropic)
     * and API key are determined at runtime by user settings.
     */
    private var currentLlmService: LlmService? = null

    /** Test hook for injecting a deterministic LLM service without touching DI. */
    internal var llmServiceOverride: LlmService? = null

    /**
     * Create a provider-specific [LlmService] from the user's config.
     *
     * Uses [LlmServiceFactory] to dispatch to the correct implementation,
     * passing a dummy client for the non-selected provider (the factory
     * only uses the client matching the selected provider).
     */
    private fun createLlmService(provider: LlmProvider): LlmService {
        return when (provider) {
            is LlmProvider.OpenAI -> {
                val openAiClient = OpenAiClient(okHttpClient, provider.apiKey, provider.baseUrl)
                val dummyAnthropic = AnthropicClient(okHttpClient, "", "")
                LlmServiceFactory.create(provider, openAiClient, dummyAnthropic)
            }
            is LlmProvider.Anthropic -> {
                val dummyOpenAi = OpenAiClient(okHttpClient, "", "")
                val anthropicClient = AnthropicClient(okHttpClient, provider.apiKey, provider.baseUrl)
                LlmServiceFactory.create(provider, dummyOpenAi, anthropicClient)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /**
     * Execute translation pipeline for a batch of texts.
     *
     * Each text is preprocessed into segments. All non-empty segments
     * are batched together, sent to the LLM, and the results are
     * re-grouped per original text via [TextPreprocessor.postprocess].
     *
     * @param texts  Raw source texts to translate
     * @param config Translation configuration (mode, model, languages, glossary, batchSize, concurrency)
     * @return Cold flow emitting [BatchResult] events as the pipeline progresses
     */
    fun executeBatch(
        texts: List<String>,
        config: TranslationConfig,
        projectId: String = CacheManager.DEFAULT_PROJECT_ID
    ): Flow<BatchResult> = flow {
        if (texts.isEmpty()) {
            emit(BatchResult.Success(
                batchIndex = 0, items = emptyList(), tokensUsed = 0,
                inputTokens = 0, outputTokens = 0, cacheTokens = 0
            ))
            return@flow
        }

        // Dynamically create the provider-specific LlmService from user config
        currentLlmService = llmServiceOverride ?: createLlmService(config.model.provider)

        emit(BatchResult.Started(batchIndex = 0, size = texts.size))

        val emitProgress: suspend (BatchResult) -> Unit = { emit(it) }

        val result = orchestrate(texts, config, projectId, emitProgress)

        emit(result)
    }

    // ──────────────────────────────────────────────
    //  Orchestration with chunked + concurrent processing
    // ──────────────────────────────────────────────

    private suspend fun orchestrate(
        texts: List<String>,
        config: TranslationConfig,
        projectId: String,
        emitProgress: suspend (BatchResult) -> Unit
    ): BatchResult {
        val batchSize = config.batchSize.coerceAtLeast(1)

        // ── Phase 1: Preprocess ALL texts globally ──
        val allTextSegments = texts.mapIndexed { idx, text ->
            val result = TextPreprocessor.preprocess(text)
            TextSegments(idx, result.segments, result.metadata)
        }

        // Collect all non-empty segments with global numbering.
        // Also build a (textIdx, segIdx) → globalPos map for cache-saving rebuild.
        val globalNonEmptyItems = mutableListOf<Pair<Int, String>>()  // (globalIndex, text)
        val posToTextIndex = mutableMapOf<Int, Int>()  // globalNonEmptyItems position → textIndex
        val segmentGlobalPosMap = mutableMapOf<Pair<Int, Int>, Int>()  // (textIdx, segIdx) → globalPos
        for ((textIdx, ts) in allTextSegments.withIndex()) {
            for ((segIdx, seg) in ts.segments.withIndex()) {
                if (!seg.isEmpty) {
                    val pos = globalNonEmptyItems.size
                    globalNonEmptyItems.add((pos + 1) to seg.text)
                    posToTextIndex[pos] = textIdx
                    segmentGlobalPosMap[textIdx to segIdx] = pos
                }
            }
        }

        // Identify skip items (pure numbers, EV codes, etc.)
        val skipPositions = mutableSetOf<Int>()
        val llmPositions = mutableListOf<Int>()  // positions in globalNonEmptyItems that need LLM
        val textToLlmPositions = mutableMapOf<Int, MutableList<Int>>()  // textIdx → its LLM-bound positions
        for ((pos, item) in globalNonEmptyItems.withIndex()) {
            if (SkipPatterns.shouldSkip(item.second)) {
                skipPositions.add(pos)
                AppLogger.d(TAG, "Skip segment #${item.first}: \"${item.second}\"")
            } else {
                llmPositions.add(pos)
            }
        }
        // Build text→LLM-positions map
        for (pos in llmPositions) {
            val textIdx = posToTextIndex[pos]!!
            textToLlmPositions.getOrPut(textIdx) { mutableListOf() }.add(pos)
        }

        // Pre-fill result map with skip passthroughs
        val globalResultMap = mutableMapOf<Int, String>()
        for (pos in skipPositions) {
            globalResultMap[pos] = globalNonEmptyItems[pos].second
        }

        var cacheTokensIncrement = 0
        var cacheHitCount = 0

        // ── Phase 2: Cache check (batch) — skip already-translated texts ──
        val llmTexts = llmPositions.map { globalNonEmptyItems[it].second }
        val cachedMap = cacheManager.getCachedBatch(llmTexts, config.mode, config.model.modelId, projectId)
        val llmIterator = llmPositions.iterator()
        while (llmIterator.hasNext()) {
            val pos = llmIterator.next()
            val itemText = globalNonEmptyItems[pos].second
            val cachedContent = cachedMap[itemText]
            if (cachedContent != null) {
                globalResultMap[pos] = cachedContent
                cacheTokensIncrement += TokenEstimator.estimate(itemText)
                cacheHitCount++
                llmIterator.remove()
                // Remove from textToLlmPositions
                val textIdx = posToTextIndex[pos]!!
                val textPositions = textToLlmPositions[textIdx]
                if (textPositions != null) {
                    textPositions.remove(pos)
                    if (textPositions.isEmpty()) textToLlmPositions.remove(textIdx)
                }
            }
        }

        AppLogger.d(TAG, "Orchestrate: ${texts.size} texts → ${globalNonEmptyItems.size} segments → " +
            "${skipPositions.size} skip + ${llmPositions.size} LLM + ${cacheTokensIncrement} cached tokens")

        // Track saved texts to avoid duplicate cache writes
        val savedTexts = mutableSetOf<Int>()
        // Track failed positions to skip cache writes for items that couldn't be translated
        val failedGlobalPositions = mutableSetOf<Int>()

        // ── Phase 3: Build glossary section once (same for all chunks) ──
        val glossaryEntries = config.glossaryEntries.map { entity ->
            GlossaryEntry(
                source = entity.sourceTerm,
                target = entity.targetTerm,
                isRegex = entity.matchType == GlossaryEntryEntity.MATCH_TYPE_REGEX,
                isCaseSensitive = entity.matchType != GlossaryEntryEntity.MATCH_TYPE_CASE_INSENSITIVE,
                remark = entity.info
            )
        }
        // Context-aware filtering: only include terms that appear in the batch texts
        val batchTexts = llmPositions.map { globalNonEmptyItems[it].second }
        val relevantGlossary = GlossaryEngine.filterByTexts(glossaryEntries, batchTexts)
        val glossarySection = GlossaryEngine.buildGlossarySection(relevantGlossary, config.mode)
        val prohibitionSection = if (config.mode == TranslationMode.TRANSLATE) {
            GlossaryEngine.buildProhibitionSection(glossaryEntries)
        } else ""

        // ── Phase 3: Chunk LLM items by batchSize (after filtering!) ──
        val llmChunks: List<List<Int>> = llmPositions.chunked(batchSize)

        // Emit initial progress: skip + cache hits are immediately "done"
        var completedCount = skipPositions.size + cacheHitCount
        emitProgress(
            BatchResult.Progress(
                batchIndex = 0,
                completed = completedCount,
                total = texts.size,
                stage = "过滤 ${skipPositions.size} 条跳过项",
                inputTokens = 0,
                outputTokens = 0,
                cacheTokens = cacheTokensIncrement
            )
        )

        var totalTokens = 0
        var totalInputTokens = 0
        var totalOutputTokens = 0

        // Concurrency: process chunks in groups, each group in parallel
        val concurrencyLimit = config.concurrency.coerceIn(1, 10)
        val semaphore = Semaphore(concurrencyLimit)

        llmChunks.chunked(concurrencyLimit).forEach { group ->
            coroutineScope {
                val deferreds = group.map { chunkPositions ->
                    async {
                        semaphore.withPermit {
                            val chunkItems = chunkPositions.map { pos ->
                                globalNonEmptyItems[pos]
                            }
                            val chunkResult = executeLlmAgentLoop(
                                chunkItems, config, glossarySection, prohibitionSection, relevantGlossary
                            )
                            // Map local positions back to global positions
                            val globalTranslations = mutableMapOf<Int, String>()
                            val globalFailedPositions = mutableSetOf<Int>()
                            for ((localPos, translation) in chunkResult.translations) {
                                globalTranslations[chunkPositions[localPos]] = translation
                            }
                            for (localFailedPos in chunkResult.failedPositions) {
                                globalFailedPositions.add(chunkPositions[localFailedPos])
                            }
                            // Track failed positions for cache-skipping
                            failedGlobalPositions.addAll(globalFailedPositions)
                            Pair(chunkPositions, LlmChunkResult(
                                translations = globalTranslations,
                                tokensUsed = chunkResult.tokensUsed,
                                inputTokens = chunkResult.inputTokens,
                                outputTokens = chunkResult.outputTokens,
                                failedPositions = globalFailedPositions.toSet()
                            ))
                        }
                    }
                }
                for (deferred in deferreds) {
                    val (chunkPositions, chunkResult) = deferred.await()
                    globalResultMap.putAll(chunkResult.translations)
                    totalTokens += chunkResult.tokensUsed
                    totalInputTokens += chunkResult.inputTokens
                    totalOutputTokens += chunkResult.outputTokens
                    completedCount += chunkPositions.size
                    // Incremental cache save: check for fully-completed texts
                    saveCompletedTexts(
                        chunkPositions, globalResultMap, posToTextIndex,
                        texts, allTextSegments, textToLlmPositions,
                        savedTexts, segmentGlobalPosMap, globalNonEmptyItems, config, projectId,
                        failedGlobalPositions, relevantGlossary
                    )
                    emitProgress(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = completedCount,
                            total = texts.size,
                            stage = "翻译中 $completedCount/${texts.size}",
                            inputTokens = totalInputTokens,
                            outputTokens = totalOutputTokens,
                            cacheTokens = cacheTokensIncrement
                        )
                    )
                }
            }
        }

        // ── Segment-level verification: detect untranslated segments (source == translated) ──
        val segmentVerifyItems = llmPositions.map { pos ->
            pos to globalNonEmptyItems[pos].second
        }
        val segmentVerifyResult = TranslationVerifier.verify(segmentVerifyItems, globalResultMap, relevantGlossary)

        if (segmentVerifyResult.failed.isNotEmpty()) {
            emitProgress(
                BatchResult.VerificationComplete(
                    totalItems = llmPositions.size,
                    failedCount = segmentVerifyResult.failed.size,
                    failedItems = segmentVerifyResult.failed
                )
            )

            val remainingFailed = retryFailedItems(
                failedItems = segmentVerifyResult.failed,
                config = config,
                originalBatchSize = batchSize,
                glossaryEntries = relevantGlossary,
                glossarySection = glossarySection,
                prohibitionSection = prohibitionSection,
                globalNonEmptyItems = globalNonEmptyItems,
                onSuccess = { globalIndex, translation ->
                    globalResultMap[globalIndex] = translation
                },
                onTokens = { used, input, output ->
                    totalTokens += used
                    totalInputTokens += input
                    totalOutputTokens += output
                },
                emit = emitProgress
            )

            // Mark permanently failed items so they're excluded from cache writes
            for (failed in remainingFailed) {
                failedGlobalPositions.add(failed.globalIndex)
            }

            // Update completed count for retry-recovered items
            val retrySuccessCount = segmentVerifyResult.failed.size - remainingFailed.size
            completedCount += retrySuccessCount

            if (retrySuccessCount > 0) {
                emitProgress(
                    BatchResult.Progress(
                        batchIndex = 0,
                        completed = completedCount,
                        total = texts.size,
                        stage = "重试成功 $retrySuccessCount 项 (${remainingFailed.size} 项永久失败)",
                        inputTokens = totalInputTokens,
                        outputTokens = totalOutputTokens,
                        cacheTokens = cacheTokensIncrement
                    )
                )
            }
        }

        // ── Phase 4: Rebuild texts from all segments ──
        val mergedSegments = globalNonEmptyItems.indices.map {
            globalResultMap[it] ?: globalNonEmptyItems[it].second
        }
        val translatedItems = rebuildTexts(allTextSegments, mergedSegments)

        // ── Phase 5: Save any remaining unsaved texts ──
        // (texts whose segments straddled chunk boundaries or weren't completed
        //  by saveCompletedTexts for any reason)
        // Skip texts that have failed positions (API/network errors)
        try {
            for ((textIdx, translated) in translatedItems.withIndex()) {
                if (textIdx in savedTexts) continue
                val llmPositions = textToLlmPositions[textIdx] ?: continue
                // Skip texts that have failed positions
                if (llmPositions.any { it in failedGlobalPositions }) continue
                cacheManager.saveToCache(
                    sourceText = texts[textIdx],
                    translation = translated,
                    mode = config.mode,
                    modelId = config.model.modelId,
                    projectId = projectId
                )
                savedTexts.add(textIdx)
            }
            AppLogger.d(TAG, "Cache final: saved ${savedTexts.size}/${texts.size} texts for project [$projectId]")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Final cache save failed (non-fatal)", e)
        }

        return if (translatedItems.isNotEmpty()) {
            BatchResult.Success(
                batchIndex = 0, items = translatedItems,
                tokensUsed = totalTokens,
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cacheTokens = cacheTokensIncrement
            )
        } else {
            BatchResult.Failure(
                batchIndex = 0,
                error = TranslationException("Translation yielded no results")
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Control character preservation check
    // ──────────────────────────────────────────────

    /**
     * Verifies that meaningful control characters (\n, \r, \t) from the
     * source text are preserved in the translation. Compares character counts.
     */
    private fun hasControlCharsPreserved(source: String, translated: String): Boolean {
        val controlChars = setOf('\n', '\r', '\t')
        for (ch in controlChars) {
            val srcCount = source.count { it == ch }
            val tgtCount = translated.count { it == ch }
            if (srcCount > 0 && tgtCount < srcCount) {
                return false
            }
        }
        return true
    }

    // ──────────────────────────────────────────────
    //  Text fallback (no tool call output)
    // ──────────────────────────────────────────────

    /**
     * Fallback: validates raw text response, extracts translations via
     * [ResponseExtractor], and fills [resultMap] for the remaining positions.
     *
     * Used when the LLM does not return a tool call (e.g., model doesn't
     * support tool calling, or the response format is unexpected).
     */
    private fun executeTextFallback(
        response: TranslationResponse,
        resultMap: MutableMap<Int, String>,
        remainingPositions: List<Int>,
        items: List<Pair<Int, String>>,
        config: TranslationConfig
    ) {
        // Validate raw response
        val validation = ResponseChecker.validateResponse(
            response.content,
            remainingPositions.size
        )
        val validationLabel = when (validation) {
            is ValidationResult.Valid -> "Valid"
            is ValidationResult.TooFew -> "TooFew"
            is ValidationResult.TooMany -> "TooMany"
            is ValidationResult.Refused -> "Refused"
            is ValidationResult.WrongLanguage -> "WrongLanguage"
        }
        AppLogger.d(TAG, "Text fallback validation: $validationLabel (expected ${remainingPositions.size} items)")
        AppLogger.d(TAG, "Text fallback response length: ${response.content.length} chars")

        if (validation != ValidationResult.Valid) {
            for (pos in remainingPositions) {
                resultMap[pos] = items[pos].second
            }
            return
        }

        // Extract translations
        val extractionResult = ResponseExtractor.parse(response.content)
        val extractedSegments = when (extractionResult) {
            is ExtractionResult.Success -> {
                AppLogger.d(TAG, "Extraction success: ${extractionResult.translations.size}/${remainingPositions.size} items")
                extractionResult.translations
            }
            is ExtractionResult.Error -> {
                AppLogger.w(TAG, "Extraction failed (passthrough): ${extractionResult.message}")
                for (pos in remainingPositions) {
                    resultMap[pos] = items[pos].second
                }
                return
            }
        }

        // Match by position order
        val count = minOf(extractedSegments.size, remainingPositions.size)
        for (i in 0 until count) {
            resultMap[remainingPositions[i]] = extractedSegments[i]
        }
        // Passthrough any surplus
        for (i in count until remainingPositions.size) {
            resultMap[remainingPositions[i]] = items[remainingPositions[i]].second
        }
    }

    // ──────────────────────────────────────────────
    //  Internal types
    // ──────────────────────────────────────────────

    /** Per-text segment metadata for post-processing. */
    private data class TextSegments(
        val originalIndex: Int,
        val segments: List<Segment>,
        val metadata: PreprocessingMetadata
    )

    /** Result of running the LLM agent loop on a single chunk of pre-filtered items. */
    private data class LlmChunkResult(
        val translations: Map<Int, String>,  // local position → translation
        val tokensUsed: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val failedPositions: Set<Int> = emptySet()  // positions that were passthrough (API/network error)
    )

    // ──────────────────────────────────────────────
    //  LLM agent loop (operates on pre-filtered items)
    // ──────────────────────────────────────────────

    /**
     * Runs the tool-calling agent loop on a chunk of pre-filtered items.
     * Items are guaranteed to be non-empty and not matching SkipPatterns.
     *
     * @param items  (globalIndex, text) pairs — all LLM-bound, no passthrough
     * @return [LlmChunkResult] with position → translation map + token usage
     */
    private suspend fun executeLlmAgentLoop(
        items: List<Pair<Int, String>>,
        config: TranslationConfig,
        glossarySection: String,
        prohibitionSection: String,
        glossaryEntries: List<GlossaryEntry> = emptyList()
    ): LlmChunkResult {
        if (items.isEmpty()) {
            return LlmChunkResult(translations = emptyMap(), tokensUsed = 0, inputTokens = 0, outputTokens = 0, failedPositions = emptySet())
        }

        // Token estimation
        val segmentTexts = items.map { it.second }
        val initPrompt = buildPrompt(items, config, glossarySection, prohibitionSection, "")
        val estimatedTokens = TokenEstimator.estimateBatch(
            segmentTexts, initPrompt.systemPrompt, "\n1. "
        )

        // Context window check
        if (!TokenEstimator.canFitInContext(estimatedTokens, config.model)) {
            AppLogger.w(TAG, "Chunk of ${items.size} items exceeds context window, passthrough all")
            return LlmChunkResult(
                translations = items.indices.associate { it to items[it].second },
                tokensUsed = 0, inputTokens = 0, outputTokens = 0, failedPositions = emptySet()
            )
        }

        // Rate limit
        try {
            rateLimiter.acquire(estimatedTokens)
        } catch (e: RateLimitException) {
            AppLogger.w(TAG, "Rate limited, passthrough ${items.size} items")
            return LlmChunkResult(
                translations = items.indices.associate { it to items[it].second },
                tokensUsed = 0, inputTokens = 0, outputTokens = 0, failedPositions = items.indices.toSet()
            )
        }

        val localResultMap = mutableMapOf<Int, String>()  // local position → translation
        val remainingLocalPositions = items.indices.toMutableList()
        val failedPositions = mutableSetOf<Int>()  // positions that failed due to hard errors
        var totalTokens = 0
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var iteration = 0
        val MAX_ITERATIONS = 5

        // ── Store original texts for retry glossary re-protection ──
        // When retrying, we need to re-protect using ORIGINAL text, not the
        // already-protected text (with {GLO_0} placeholders) that the LLM sees.
        val originalTexts: Map<Int, String> = items.indices.associate { idx ->
            idx to items[idx].second
        }

        // ── Glossary protect: replace source terms with {GLO_N} placeholders ──
        // Prevents the LLM from mistranslating known terms; placeholders are
        // restored to target terms after receiving the LLM response.
        // MutableMap to allow re-protection of retry items with original texts.
        val itemProtectResults: MutableMap<Int, ProtectResult> = if (glossaryEntries.isNotEmpty()) {
            items.indices.associate { idx ->
                idx to GlossaryEngine.protect(items[idx].second, glossaryEntries)
            }.toMutableMap()
        } else {
            mutableMapOf()
        }

        fun protectedText(pos: Int): String =
            itemProtectResults[pos]?.protectedText ?: items[pos].second

        fun placeholdersFor(pos: Int): Map<String, String> =
            itemProtectResults[pos]?.placeholders ?: emptyMap()

        fun restoreTranslation(pos: Int, translated: String): String =
            GlossaryEngine.restore(translated, placeholdersFor(pos))

        // Track retry items that need re-protection with original texts
        val retryOriginalTexts: MutableMap<Int, String> = mutableMapOf()

        while (remainingLocalPositions.isNotEmpty() && iteration < MAX_ITERATIONS) {
            iteration++

            // Re-protect retry items using original texts (not already-protected text)
            if (retryOriginalTexts.isNotEmpty()) {
                for (pos in retryOriginalTexts.keys) {
                    if (glossaryEntries.isNotEmpty()) {
                        itemProtectResults[pos] = GlossaryEngine.protect(retryOriginalTexts[pos]!!, glossaryEntries)
                    }
                }
                retryOriginalTexts.clear()
            }

            // Use protected text for prompt building
            val protectedPairs = remainingLocalPositions.map { pos ->
                items[pos].first to protectedText(pos)
            }
            val retryPrompt = buildPrompt(protectedPairs, config, glossarySection, prohibitionSection, "")

            val response = try {
                val llmConfig = LlmRequestConfig(
                    messages = listOf(LlmRequestConfig.Message("user", retryPrompt.userMessage)),
                    systemPrompt = retryPrompt.systemPrompt,
                    model = config.model,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    toolChoice = "output_translations"
                )
                currentLlmService!!.translate(llmConfig)
            } catch (e: ApiException) {
                AppLogger.w(TAG, "API error iteration $iteration (passthrough ${remainingLocalPositions.size} items): ${e.message}")
                for (pos in remainingLocalPositions) {
                    localResultMap[pos] = items[pos].second
                    failedPositions.add(pos)
                }
                remainingLocalPositions.clear()
                break
            } catch (e: NetworkException) {
                AppLogger.w(TAG, "Network error iteration $iteration (passthrough ${remainingLocalPositions.size} items): ${e.message}")
                for (pos in remainingLocalPositions) {
                    localResultMap[pos] = items[pos].second
                    failedPositions.add(pos)
                }
                remainingLocalPositions.clear()
                break
            }
            totalTokens += response.tokensUsed
            totalInputTokens += response.inputTokens
            totalOutputTokens += response.outputTokens
            AppLogger.i(TAG, "LLM response iteration $iteration: ${response.content.length} chars, ${response.tokensUsed} tokens (in=${response.inputTokens}, out=${response.outputTokens}), model=${response.model}")

            // Tool call result: {source, translated} — positional match
            if (response.translationPairs != null) {
                val numPrefix = Regex("""^\d+[.\、]\s*""")
                val count = minOf(response.translationPairs.size, remainingLocalPositions.size)
                val newlineRetry = mutableListOf<Int>()
                for (i in 0 until count) {
                    val globalPos = remainingLocalPositions[i]
                    val rawTranslated = response.translationPairs[i].translated
                    val originalText = items[globalPos].second
                    val translated = if (numPrefix.containsMatchIn(originalText)) {
                        rawTranslated
                    } else {
                        numPrefix.replaceFirst(rawTranslated, "")
                    }
                    // Restore glossary placeholders → target terms
                    val restored = restoreTranslation(globalPos, translated)
                    // Check that control characters (\n, \r, \t) are preserved
                    if (!hasControlCharsPreserved(originalText, restored)) {
                        AppLogger.d(TAG, "Control char loss for #${items[globalPos].first}, will retry")
                        newlineRetry.add(globalPos)
                        // Store original text for re-protection on retry
                        retryOriginalTexts[globalPos] = originalTexts[globalPos] ?: items[globalPos].second
                    } else {
                        localResultMap[globalPos] = restored
                    }
                }
                // Keep unmatched items + newline-retry items for next iteration
                val toKeep = remainingLocalPositions.drop(count)
                remainingLocalPositions.clear()
                remainingLocalPositions.addAll(toKeep)
                remainingLocalPositions.addAll(newlineRetry)
                val matched = count - newlineRetry.size
                AppLogger.d(TAG, "Tool call iteration $iteration: +$matched matched, " +
                    "${newlineRetry.size} control-char-retry, ${remainingLocalPositions.size} remaining")

                if (matched == 0 && newlineRetry.isEmpty() && remainingLocalPositions.isNotEmpty()) {
                    AppLogger.w(TAG, "Tool call made no progress, passthrough ${remainingLocalPositions.size} items")
                    for (pos in remainingLocalPositions) localResultMap[pos] = items[pos].second
                    remainingLocalPositions.clear()
                }
            }
            // Fallback: flat translations array
            else if (response.translations != null && response.translations.isNotEmpty()) {
                val count = minOf(response.translations.size, remainingLocalPositions.size)
                for (i in 0 until count) {
                    val pos = remainingLocalPositions[i]
                    localResultMap[pos] = restoreTranslation(pos, response.translations[i])
                }
                for (i in count until remainingLocalPositions.size) {
                    localResultMap[remainingLocalPositions[i]] = items[remainingLocalPositions[i]].second
                }
                remainingLocalPositions.clear()
                AppLogger.d(TAG, "Flat translation: $count items")
            }
            // Text fallback (no tool call output)
            else {
                AppLogger.w(TAG, "No tool call result, falling back to text parsing")
                val textFallbackPositions = remainingLocalPositions.toList()
                executeTextFallback(
                    response, localResultMap, remainingLocalPositions, items, config
                )
                // Apply restore to entries produced by text fallback
                for (pos in textFallbackPositions) {
                    val value = localResultMap[pos]
                    if (value != null) {
                        localResultMap[pos] = restoreTranslation(pos, value)
                    }
                }
                remainingLocalPositions.clear()
            }
        }

        // Passthrough any items that weren't covered in the agent loop
        for (pos in remainingLocalPositions) {
            localResultMap[pos] = items[pos].second
        }

        AppLogger.i(TAG, "Agent loop done: ${items.size} items, $totalTokens tokens (in=$totalInputTokens, out=$totalOutputTokens) over $iteration iteration(s), " +
            "${if (remainingLocalPositions.isEmpty()) "all covered" else "${remainingLocalPositions.size} passthrough"}")

        return LlmChunkResult(
            translations = localResultMap,
            tokensUsed = totalTokens,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            failedPositions = failedPositions.toSet()
        )
    }



    // ──────────────────────────────────────────────
    //  Decreasing-batch retry for failed items
    // ──────────────────────────────────────────────

    /**
     * Retries failed translation items across 3 rounds with decreasing batch sizes.
     *
     * Round 1: batch = originalBatchSize / 2
     * Round 2: batch = originalBatchSize / 4
     * Round 3: batch = 1 (single item per call)
     *
     * After round 3, items that still fail are marked permanentlyFailed.
     * Emits [BatchResult.RetryProgress] and [BatchResult.RetryComplete] events.
     *
     * @param failedItems         Items that failed initial verification
     * @param config              Translation configuration
     * @param originalBatchSize   The batch size used during initial translation
     * @param glossaryEntries     Glossary entries for term protection during retry
     * @param glossarySection     Pre-built glossary section for prompt
     * @param prohibitionSection  Pre-built prohibition section for prompt
     * @param globalNonEmptyItems All non-empty segments mapped as (globalIndex, text)
     * @param onSuccess           Called when a retried item succeeds: (globalIndex, translation)
     * @param onTokens            Called to accumulate token usage: (used, input, output)
     * @param emit                Callback to emit BatchResult events
     * @return Remaining failed items after all retry rounds
     */
    private suspend fun retryFailedItems(
        failedItems: List<FailedItem>,
        config: TranslationConfig,
        originalBatchSize: Int,
        glossaryEntries: List<GlossaryEntry>,
        glossarySection: String,
        prohibitionSection: String,
        globalNonEmptyItems: List<Pair<Int, String>>,
        onSuccess: (globalIndex: Int, translation: String) -> Unit,
        onTokens: (used: Int, input: Int, output: Int) -> Unit,
        emit: suspend (BatchResult) -> Unit
    ): List<FailedItem> {
        var remainingFailed = failedItems.toMutableList()

        for (round in 1..3) {
            if (remainingFailed.isEmpty()) break

            val batchSize = when (round) {
                1 -> (originalBatchSize / 2).coerceAtLeast(1)
                2 -> (originalBatchSize / 4).coerceAtLeast(1)
                3 -> 1
                else -> 1
            }

            emit(BatchResult.RetryProgress(round, completed = 0, total = remainingFailed.size))
            AppLogger.i(TAG, "Retry round $round: ${remainingFailed.size} items, batchSize=$batchSize")

            val chunks = remainingFailed.chunked(batchSize)
            val newRemaining = mutableListOf<FailedItem>()
            var completedInRound = 0

            for (chunk in chunks) {
                // Build items for executeLlmAgentLoop: map FailedItem.globalIndex → globalNonEmptyItems entry
                val items = chunk.map { failed ->
                    globalNonEmptyItems[failed.globalIndex]
                }

                val chunkResult = executeLlmAgentLoop(
                    items, config, glossarySection, prohibitionSection, glossaryEntries
                )

                onTokens(chunkResult.tokensUsed, chunkResult.inputTokens, chunkResult.outputTokens)

                // Process results: check which items succeeded vs still failed
                for ((localPos, translation) in chunkResult.translations) {
                    val failedItem = chunk[localPos]
                    val globalIndex = failedItem.globalIndex
                    val sourceText = globalNonEmptyItems[globalIndex].second

                    val trimmedSource = sourceText.trim()
                    val trimmedTranslation = translation.trim()

                    val glossaryApplied = GlossaryEngine.verifyApplied(trimmedSource, trimmedTranslation, glossaryEntries)
                    if (trimmedSource != trimmedTranslation &&
                        glossaryApplied &&
                        chunkResult.failedPositions.contains(localPos).not()
                    ) {
                        // Success: translation differs from source and satisfies glossary targets.
                        onSuccess(globalIndex, translation)
                        completedInRound++
                        AppLogger.d(TAG, "Retry round $round: item #${globalIndex} succeeded")
                    } else {
                        // Still failed or was passthrough due to error
                        val newRetryCount = failedItem.retryCount + 1
                        val isPermanent = round >= 3
                        newRemaining.add(
                            failedItem.copy(
                                retryCount = newRetryCount,
                                permanentlyFailed = isPermanent
                            )
                        )
                    }
                }

                emit(
                    BatchResult.RetryProgress(
                        round = round,
                        completed = completedInRound,
                        total = remainingFailed.size
                    )
                )
            }

            remainingFailed = newRemaining
            AppLogger.i(TAG, "Retry round $round done: ${completedInRound} succeeded, ${remainingFailed.size} still failed")
        }

        emit(BatchResult.RetryComplete(remainingFailed))
        AppLogger.i(TAG, "Retry complete: ${remainingFailed.size} items permanently failed (${failedItems.size - remainingFailed.size} recovered)")
        return remainingFailed
    }

    // ──────────────────────────────────────────────
    //  Incremental cache per chunk
    // ──────────────────────────────────────────────

    /**
     * After a chunk completes, find texts whose LLM-bound segments are now fully
     * translated, rebuild them from their segment translations, and save to cache.
     * Skips texts containing failed positions (API/network errors).
     */
    private suspend fun saveCompletedTexts(
        chunkPositions: List<Int>,
        globalResultMap: Map<Int, String>,
        posToTextIndex: Map<Int, Int>,
        texts: List<String>,
        allTextSegments: List<TextSegments>,
        textToLlmPositions: Map<Int, List<Int>>,
        savedTexts: MutableSet<Int>,
        segmentGlobalPosMap: Map<Pair<Int, Int>, Int>,
        globalNonEmptyItems: List<Pair<Int, String>>,
        config: TranslationConfig,
        projectId: String,
        failedGlobalPositions: Set<Int> = emptySet(),
        glossaryEntries: List<GlossaryEntry> = emptyList()
    ) {
        val touchedTexts = mutableSetOf<Int>()
        for (pos in chunkPositions) {
            val textIdx = posToTextIndex[pos] ?: continue
            touchedTexts.add(textIdx)
        }

        for (textIdx in touchedTexts) {
            if (textIdx in savedTexts) continue
            val llmPositions = textToLlmPositions[textIdx] ?: continue
            // Skip texts that have failed positions (API/network errors) - don't cache partial results
            if (llmPositions.any { it in failedGlobalPositions }) continue
            if (!llmPositions.all { it in globalResultMap }) continue
            val verification = TranslationVerifier.verify(
                items = llmPositions.map { pos -> pos to globalNonEmptyItems[pos].second },
                translations = globalResultMap,
                glossaryEntries = glossaryEntries
            )
            if (verification.failed.isNotEmpty()) continue

            try {
                val ts = allTextSegments[textIdx]
                val segTranslations = mutableListOf<String>()
                for ((segIdx, seg) in ts.segments.withIndex()) {
                    if (seg.isEmpty) {
                        segTranslations.add("")
                    } else {
                        val globalPos = segmentGlobalPosMap[textIdx to segIdx]
                            ?: continue
                        segTranslations.add(globalResultMap[globalPos] ?: seg.text)
                    }
                }
                val rebuilt = TextPreprocessor.postprocess(segTranslations, ts.metadata)
                cacheManager.saveToCache(
                    sourceText = texts[textIdx],
                    translation = rebuilt,
                    mode = config.mode,
                    modelId = config.model.modelId,
                    projectId = projectId
                )
                savedTexts.add(textIdx)
            } catch (e: Exception) {
                AppLogger.e(TAG, "saveCompletedTexts failed for text #$textIdx", e)
            }
        }

        if (touchedTexts.isNotEmpty()) {
            AppLogger.d(TAG, "Cache chunk: saved ${savedTexts.size} texts so far")
        }
    }

    // ──────────────────────────────────────────────
    //  Rejoin segments per text
    // ──────────────────────────────────────────────

    /**
     * Rebuilds translated texts from extracted segments,
     * applying [TextPreprocessor.postprocess] for each original text.
     */
    private fun rebuildTexts(
        textSegmentsList: List<TextSegments>,
        extractedSegments: List<String>
    ): List<String> {
        val results = mutableListOf<String>()
        var extractionCursor = 0

        for (ts in textSegmentsList) {
            val hasNonEmpty = ts.segments.any { !it.isEmpty }

            if (!hasNonEmpty) {
                results.add("")
                continue
            }

            val translatedSegments = mutableListOf<String>()

            for (seg in ts.segments) {
                if (seg.isEmpty) {
                    translatedSegments.add("")
                } else {
                    if (extractionCursor < extractedSegments.size) {
                        translatedSegments.add(extractedSegments[extractionCursor])
                        extractionCursor++
                    } else {
                        translatedSegments.add("") // fallback
                    }
                }
            }

            val rejoined = TextPreprocessor.postprocess(translatedSegments, ts.metadata)
            results.add(rejoined)
        }

        return results
    }

    // ──────────────────────────────────────────────
    //  Prompt building (mode dispatch)
    // ──────────────────────────────────────────────

    private fun buildPrompt(
        items: List<Pair<Int, String>>,
        config: TranslationConfig,
        glossarySection: String,
        prohibitionSection: String,
        previousContext: String
    ): com.mtt.app.domain.prompt.PromptResult {
        return when (config.mode) {
            TranslationMode.TRANSLATE -> PromptBuilder.buildTranslatePrompt(
                items, config.sourceLang, config.targetLang,
                glossarySection, prohibitionSection, previousContext
            )
            TranslationMode.POLISH -> PromptBuilder.buildPolishPrompt(
                items, config.sourceLang, config.targetLang,
                glossarySection, previousContext
            )
            TranslationMode.PROOFREAD -> PromptBuilder.buildProofreadPrompt(
                items, config.sourceLang, config.targetLang, glossarySection
            )
        }
    }
}
