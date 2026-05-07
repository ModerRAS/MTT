package com.mtt.app.domain.pipeline

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.TranslationException
import com.mtt.app.core.logger.AppLogger
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
    private val rateLimiter: RateLimiter
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
        config: TranslationConfig
    ): Flow<BatchResult> = flow {
        if (texts.isEmpty()) {
            emit(BatchResult.Success(batchIndex = 0, items = emptyList(), tokensUsed = 0))
            return@flow
        }

        // Dynamically create the provider-specific LlmService from user config
        currentLlmService = createLlmService(config.model.provider)

        emit(BatchResult.Started(batchIndex = 0, size = texts.size))

        val emitProgress: suspend (BatchResult) -> Unit = { emit(it) }

        val result = orchestrate(texts, config, emitProgress)

        emit(result)
    }

    // ──────────────────────────────────────────────
    //  Orchestration with chunked + concurrent processing
    // ──────────────────────────────────────────────

    private suspend fun orchestrate(
        texts: List<String>,
        config: TranslationConfig,
        emitProgress: suspend (BatchResult) -> Unit
    ): BatchResult {
        // Chunk texts by user-configured batch size.
        // Each chunk is an independent unit sent to the LLM in one API call.
        val chunks = texts.chunked(config.batchSize.coerceAtLeast(1))

        val allItems = mutableListOf<String>()
        var totalTokens = 0
        var completedSize = 0

        // Use a semaphore to limit concurrency to the user-configured value.
        // Process chunks in groups: for each group, launch up to [config.concurrency]
        // chunks in parallel via coroutineScope + async, then collect results in order.
        val concurrencyLimit = config.concurrency.coerceIn(1, 10)
        val semaphore = Semaphore(concurrencyLimit)

        // Process chunks in submission order to preserve result ordering.
        // Groups are sequential; within each group chunks run concurrently,
        // limited by the semaphore to [concurrencyLimit] at a time.
        chunks.chunked(concurrencyLimit).forEach { group ->
            coroutineScope {
                @Suppress("UNCHECKED_CAST")
                val deferreds: List<kotlinx.coroutines.Deferred<Pair<List<String>, ChunkResult>>> =
                    group.map { chunk ->
                        async {
                            semaphore.withPermit {
                                Pair(chunk, processSingleChunk(chunk, config))
                            }
                        }
                    }
                // Collect results in group order (chunked order = original order)
                for (deferred in deferreds) {
                    val (chunk, chunkResult) = deferred.await()
                    allItems.addAll(chunkResult.items)
                    totalTokens += chunkResult.tokensUsed
                    completedSize += chunk.size
                    emitProgress(
                        BatchResult.Progress(
                            batchIndex = 0,
                            completed = completedSize,
                            total = texts.size,
                            stage = "Processing batch $completedSize/${texts.size}"
                        )
                    )
                }
            }
        }

        return if (allItems.isNotEmpty()) {
            BatchResult.Success(batchIndex = 0, items = allItems, tokensUsed = totalTokens)
        } else {
            BatchResult.Failure(
                batchIndex = 0,
                error = TranslationException("Translation yielded no results")
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Internal types
    // ──────────────────────────────────────────────

    private data class ChunkResult(
        val items: List<String>,
        val tokensUsed: Int,
        val outcome: ChunkOutcome
    )

    private sealed class ChunkOutcome {
        data object Success : ChunkOutcome()
        data class Invalid(val reason: String) : ChunkOutcome()
    }

    /** Per-text segment metadata for post-processing. */
    private data class TextSegments(
        val originalIndex: Int,
        val segments: List<Segment>,
        val metadata: PreprocessingMetadata
    )

    // ──────────────────────────────────────────────
    //  Single chunk pipeline
    // ──────────────────────────────────────────────

    private suspend fun processSingleChunk(
        chunk: List<String>,
        config: TranslationConfig
    ): ChunkResult {
        // 1. Preprocess each text into segments
        val textSegmentsList = chunk.mapIndexed { idx, text ->
            val result = TextPreprocessor.preprocess(text)
            TextSegments(idx, result.segments, result.metadata)
        }

        // Collect non-empty segments with global numbering
        val nonEmptyItems = mutableListOf<Pair<Int, String>>()
        var globalIndex = 0

        for (ts in textSegmentsList) {
            for (seg in ts.segments) {
                if (!seg.isEmpty) {
                    globalIndex++
                    nonEmptyItems.add(globalIndex to seg.text)
                }
            }
        }

        if (nonEmptyItems.isEmpty()) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = 0,
                outcome = ChunkOutcome.Success
            )
        }

        // 1b. Filter out items that need no translation — pure numbers, EV codes, etc.
        val passthroughPatterns = listOf(
            Regex("""^\d+$"""),                      // pure digits: "0", "123"
            Regex("""^EV\d+$""", RegexOption.IGNORE_CASE)  // EV codes: "EV001", "ev074"
        )
        val numericPositions = mutableSetOf<Int>()
        val llmNonEmptyItems = mutableListOf<Pair<Int, String>>()
        for ((pos, item) in nonEmptyItems.withIndex()) {
            val trimmed = item.second.trim()
            val shouldSkip = trimmed.toDoubleOrNull() != null ||
                passthroughPatterns.any { it.matches(trimmed) }
            if (shouldSkip) {
                numericPositions.add(pos)
                AppLogger.d(TAG, "Passthrough segment #${item.first}: \"${item.second}\"")
            } else {
                llmNonEmptyItems.add(item)
            }
        }

        if (llmNonEmptyItems.isEmpty()) {
            AppLogger.d(TAG, "All ${chunk.size} items in chunk are passthrough, skip LLM call")
            return ChunkResult(items = chunk, tokensUsed = 0, outcome = ChunkOutcome.Success)
        }

        // 2. Convert GlossaryEntryEntity -> GlossaryEntry
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

        // 3. Build prompt (exclude numeric items — they get passthrough)
        val prompt = buildPrompt(
            llmNonEmptyItems, config, glossarySection, prohibitionSection, ""
        )

        // 4a. Token estimation
        val segmentTexts = llmNonEmptyItems.map { it.second }
        val estimatedTokens = TokenEstimator.estimateBatch(
            segmentTexts, prompt.systemPrompt, "\n1. "
        )

        // 4b. Context window check
        if (!TokenEstimator.canFitInContext(estimatedTokens, config.model)) {
            return ChunkResult(
                items = chunk,  // passthrough original on failure
                tokensUsed = 0,
                outcome = ChunkOutcome.Success
            )
        }

        // 5. Rate limit
        try {
            rateLimiter.acquire(estimatedTokens)
        } catch (e: RateLimitException) {
            return ChunkResult(
                items = chunk,  // passthrough original on failure
                tokensUsed = 0,
                outcome = ChunkOutcome.Success
            )
        }

        // ── Tool calling + agent loop ──────────────────────────
        // Uses LLM tool calling (output_translations: {source, translated} pairs)
        // instead of raw text parsing. If some items are not covered, loops back
        // with remaining items (agent loop).

        // Map: nonEmptyItems position → translation text
        val resultMap = mutableMapOf<Int, String>()
        // Fill numeric passthroughs
        for (pos in numericPositions) {
            resultMap[pos] = nonEmptyItems[pos].second
        }

        // Remaining non-numeric positions in nonEmptyItems
        val remainingPositions = mutableListOf<Int>()
        for (pos in nonEmptyItems.indices) {
            if (pos !in numericPositions) remainingPositions.add(pos)
        }

        var totalTokens = 0
        var iteration = 0
        val MAX_ITERATIONS = 5

        while (remainingPositions.isNotEmpty() && iteration < MAX_ITERATIONS) {
            iteration++

            // Build prompt for remaining items
            val remainingItems = remainingPositions.map { pos ->
                nonEmptyItems[pos].first to nonEmptyItems[pos].second
            }
            val retryPrompt = buildPrompt(
                remainingItems, config, glossarySection, prohibitionSection, ""
            )

            // Call LLM with tool choice
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
                AppLogger.w(TAG, "API error iteration $iteration (passthrough ${remainingPositions.size} items): ${e.message}")
                for (pos in remainingPositions) resultMap[pos] = nonEmptyItems[pos].second
                remainingPositions.clear()
                break
            } catch (e: NetworkException) {
                AppLogger.w(TAG, "Network error iteration $iteration (passthrough ${remainingPositions.size} items): ${e.message}")
                for (pos in remainingPositions) resultMap[pos] = nonEmptyItems[pos].second
                remainingPositions.clear()
                break
            }
            totalTokens += response.tokensUsed
            AppLogger.i(TAG, "LLM response iteration $iteration: ${response.content.length} chars, ${response.tokensUsed} tokens, model=${response.model}")

            // Tool call result: {source, translated} pairs
            // Tool call already pairs each translation to its source — use positionally.
            // Model returns results in the same order as input items.
            if (response.translationPairs != null) {
                val count = minOf(response.translationPairs.size, remainingPositions.size)
                val toKeep = remainingPositions.drop(count)
                for (i in 0 until count) {
                    resultMap[remainingPositions[i]] = response.translationPairs[i].translated
                }
                remainingPositions.clear()
                remainingPositions.addAll(toKeep)
                AppLogger.d(TAG, "Tool call iteration $iteration: +$count matched, ${remainingPositions.size} remaining")

                if (count == 0) {
                    AppLogger.w(TAG, "Tool call made no progress, passthrough ${remainingPositions.size} items")
                    for (pos in remainingPositions) resultMap[pos] = nonEmptyItems[pos].second
                    remainingPositions.clear()
                }
            }
            // Fallback: flat translations array — match by position order
            else if (response.translations != null && response.translations.isNotEmpty()) {
                val count = minOf(response.translations.size, remainingPositions.size)
                for (i in 0 until count) {
                    resultMap[remainingPositions[i]] = response.translations[i]
                }
                if (count < remainingPositions.size) {
                    // Partial flat result — passthrough the rest
                    for (i in count until remainingPositions.size) {
                        resultMap[remainingPositions[i]] = nonEmptyItems[remainingPositions[i]].second
                    }
                }
                remainingPositions.clear()
                AppLogger.d(TAG, "Flat translation: $count items")
            }
            // No structured output — fall through to old flow
            else {
                AppLogger.w(TAG, "No tool call result, falling back to text parsing")
                processSingleChunkTextFallback(response, resultMap, remainingPositions, nonEmptyItems, config)
                remainingPositions.clear()
            }
        }

        // Build merged segments in order
        val mergedSegments = nonEmptyItems.indices.map {
            resultMap[it] ?: nonEmptyItems[it].second
        }
        AppLogger.i(TAG, "Agent loop done: ${nonEmptyItems.size} segments, " +
            "${numericPositions.size} numeric passthrough, $totalTokens tokens over $iteration iteration(s), " +
            "${if (remainingPositions.isEmpty()) "all covered" else "${remainingPositions.size} passthrough"}")

        // 10. Rejoin per original text using postprocess
        val translatedItems = rebuildTexts(textSegmentsList, mergedSegments)

        return ChunkResult(
            items = translatedItems,
            tokensUsed = totalTokens,
            outcome = ChunkOutcome.Success
        )
    }

    /**
     * Fallback: validates raw text response, extracts translations via
     * [ResponseExtractor], and fills [resultMap] for the remaining positions.
     *
     * Used when the LLM does not return a tool call (e.g., model doesn't
     * support tool calling, or the response format is unexpected).
     */
    private fun processSingleChunkTextFallback(
        response: TranslationResponse,
        resultMap: MutableMap<Int, String>,
        remainingPositions: MutableList<Int>,
        nonEmptyItems: List<Pair<Int, String>>,
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
                resultMap[pos] = nonEmptyItems[pos].second
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
                    resultMap[pos] = nonEmptyItems[pos].second
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
            resultMap[remainingPositions[i]] = nonEmptyItems[remainingPositions[i]].second
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
