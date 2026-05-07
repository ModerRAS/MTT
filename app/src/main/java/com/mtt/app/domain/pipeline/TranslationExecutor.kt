package com.mtt.app.domain.pipeline

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.TranslationException
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.llm.RateLimitException
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.llm.TokenEstimator
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
            emit(BatchResult.Success(batchIndex = 0, items = emptyList(), tokensUsed = 0))
            return@flow
        }

        // Dynamically create the provider-specific LlmService from user config
        currentLlmService = createLlmService(config.model.provider)

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

        // Collect all non-empty segments with global numbering
        val globalNonEmptyItems = mutableListOf<Pair<Int, String>>()  // (globalIndex, text)
        for (ts in allTextSegments) {
            for (seg in ts.segments) {
                if (!seg.isEmpty) {
                    globalNonEmptyItems.add((globalNonEmptyItems.size + 1) to seg.text)
                }
            }
        }

        // Identify skip items (pure numbers, EV codes, etc.)
        val skipPositions = mutableSetOf<Int>()
        val llmPositions = mutableListOf<Int>()  // positions in globalNonEmptyItems that need LLM
        for ((pos, item) in globalNonEmptyItems.withIndex()) {
            if (SkipPatterns.shouldSkip(item.second)) {
                skipPositions.add(pos)
                AppLogger.d(TAG, "Skip segment #${item.first}: \"${item.second}\"")
            } else {
                llmPositions.add(pos)
            }
        }

        AppLogger.d(TAG, "Orchestrate: ${texts.size} texts → ${globalNonEmptyItems.size} segments → " +
            "${llmPositions.size} LLM items (${skipPositions.size} skip)")

        // Pre-fill result map with skip passthroughs
        val globalResultMap = mutableMapOf<Int, String>()
        for (pos in skipPositions) {
            globalResultMap[pos] = globalNonEmptyItems[pos].second
        }

        // ── Phase 2: Build glossary section once (same for all chunks) ──
        val glossaryEntries = config.glossaryEntries.map { entity ->
            GlossaryEntry(
                source = entity.sourceTerm,
                target = entity.targetTerm,
                isRegex = entity.matchType == GlossaryEntryEntity.MATCH_TYPE_REGEX,
                isCaseSensitive = entity.matchType != GlossaryEntryEntity.MATCH_TYPE_CASE_INSENSITIVE
            )
        }
        val glossarySection = GlossaryEngine.buildGlossarySection(glossaryEntries, config.mode)
        val prohibitionSection = if (config.mode == TranslationMode.TRANSLATE) {
            GlossaryEngine.buildProhibitionSection(glossaryEntries)
        } else ""

        // ── Phase 3: Chunk LLM items by batchSize (after filtering!) ──
        val llmChunks: List<List<Int>> = llmPositions.chunked(batchSize)

        // Emit initial progress: skip items are immediately "done"
        var completedCount = skipPositions.size
        emitProgress(
            BatchResult.Progress(
                batchIndex = 0,
                completed = completedCount,
                total = texts.size,
                stage = "过滤 ${skipPositions.size} 条跳过项"
            )
        )

        var totalTokens = 0

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
                                chunkItems, config, glossarySection, prohibitionSection
                            )
                            // Map local positions back to global positions
                            val globalTranslations = mutableMapOf<Int, String>()
                            for ((localPos, translation) in chunkResult.translations) {
                                globalTranslations[chunkPositions[localPos]] = translation
                            }
                            Pair(chunkPositions, LlmChunkResult(
                                translations = globalTranslations,
                                tokensUsed = chunkResult.tokensUsed
                            ))
                        }
                    }
                }
                for (deferred in deferreds) {
                    val (chunkPositions, chunkResult) = deferred.await()
                    globalResultMap.putAll(chunkResult.translations)
                    totalTokens += chunkResult.tokensUsed
                    completedCount += chunkPositions.size
                    emitProgress(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = completedCount,
                            total = texts.size,
                            stage = "翻译中 $completedCount/${texts.size}"
                        )
                    )
                }
            }
        }

        // ── Phase 4: Rebuild texts from all segments ──
        val mergedSegments = globalNonEmptyItems.indices.map {
            globalResultMap[it] ?: globalNonEmptyItems[it].second
        }
        val translatedItems = rebuildTexts(allTextSegments, mergedSegments)

        // ── Phase 5: Save all results to cache ──
        try {
            texts.zip(translatedItems).forEach { (source, translated) ->
                cacheManager.saveToCache(
                    sourceText = source,
                    translation = translated,
                    mode = config.mode,
                    modelId = config.model.modelId,
                    projectId = projectId
                )
            }
            AppLogger.d(TAG, "Saved ${translatedItems.size} items to cache for project [$projectId]")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Cache save failed (non-fatal)", e)
        }

        return if (translatedItems.isNotEmpty()) {
            BatchResult.Success(batchIndex = 0, items = translatedItems, tokensUsed = totalTokens)
        } else {
            BatchResult.Failure(
                batchIndex = 0,
                error = TranslationException("Translation yielded no results")
            )
        }
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
        AppLogger.d(TAG, "Response preview: ${response.content.take(200).replace("\n", "\\n")}")

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
        val tokensUsed: Int
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
        prohibitionSection: String
    ): LlmChunkResult {
        if (items.isEmpty()) {
            return LlmChunkResult(translations = emptyMap(), tokensUsed = 0)
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
                tokensUsed = 0
            )
        }

        // Rate limit
        try {
            rateLimiter.acquire(estimatedTokens)
        } catch (e: RateLimitException) {
            AppLogger.w(TAG, "Rate limited, passthrough ${items.size} items")
            return LlmChunkResult(
                translations = items.indices.associate { it to items[it].second },
                tokensUsed = 0
            )
        }

        val localResultMap = mutableMapOf<Int, String>()  // local position → translation
        val remainingLocalPositions = items.indices.toMutableList()
        var totalTokens = 0
        var iteration = 0
        val MAX_ITERATIONS = 5

        while (remainingLocalPositions.isNotEmpty() && iteration < MAX_ITERATIONS) {
            iteration++

            val remainingItems = remainingLocalPositions.map { items[it] }
            val retryPrompt = buildPrompt(remainingItems, config, glossarySection, prohibitionSection, "")

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
                for (pos in remainingLocalPositions) localResultMap[pos] = items[pos].second
                remainingLocalPositions.clear()
                break
            } catch (e: NetworkException) {
                AppLogger.w(TAG, "Network error iteration $iteration (passthrough ${remainingLocalPositions.size} items): ${e.message}")
                for (pos in remainingLocalPositions) localResultMap[pos] = items[pos].second
                remainingLocalPositions.clear()
                break
            }
            totalTokens += response.tokensUsed
            AppLogger.i(TAG, "LLM response iteration $iteration: ${response.content.length} chars, ${response.tokensUsed} tokens, model=${response.model}")

            // Tool call result: {source, translated} — positional match
            if (response.translationPairs != null) {
                val numPrefix = Regex("""^\d+[.\、]\s*""")
                val count = minOf(response.translationPairs.size, remainingLocalPositions.size)
                for (i in 0 until count) {
                    val rawTranslated = response.translationPairs[i].translated
                    val originalText = items[remainingLocalPositions[i]].second
                    val translated = if (numPrefix.containsMatchIn(originalText)) {
                        rawTranslated
                    } else {
                        numPrefix.replaceFirst(rawTranslated, "")
                    }
                    localResultMap[remainingLocalPositions[i]] = translated
                }
                val toKeep = remainingLocalPositions.drop(count)
                remainingLocalPositions.clear()
                remainingLocalPositions.addAll(toKeep)
                AppLogger.d(TAG, "Tool call iteration $iteration: +$count matched, ${remainingLocalPositions.size} remaining")

                if (count == 0) {
                    AppLogger.w(TAG, "Tool call made no progress, passthrough ${remainingLocalPositions.size} items")
                    for (pos in remainingLocalPositions) localResultMap[pos] = items[pos].second
                    remainingLocalPositions.clear()
                }
            }
            // Fallback: flat translations array
            else if (response.translations != null && response.translations.isNotEmpty()) {
                val count = minOf(response.translations.size, remainingLocalPositions.size)
                for (i in 0 until count) {
                    localResultMap[remainingLocalPositions[i]] = response.translations[i]
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
                executeTextFallback(
                    response, localResultMap, remainingLocalPositions, items, config
                )
                remainingLocalPositions.clear()
            }
        }

        // Passthrough any items that weren't covered in the agent loop
        for (pos in remainingLocalPositions) {
            localResultMap[pos] = items[pos].second
        }

        AppLogger.i(TAG, "Agent loop done: ${items.size} items, $totalTokens tokens over $iteration iteration(s), " +
            "${if (remainingLocalPositions.isEmpty()) "all covered" else "${remainingLocalPositions.size} passthrough"}")

        return LlmChunkResult(translations = localResultMap, tokensUsed = totalTokens)
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
