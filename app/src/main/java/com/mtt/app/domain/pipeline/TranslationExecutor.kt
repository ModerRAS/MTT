package com.mtt.app.domain.pipeline

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.TranslationException
import com.mtt.app.data.llm.RateLimitException
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.llm.TokenEstimator
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.remote.anthropic.AnthropicClient
import com.mtt.app.data.remote.llm.LlmService
import com.mtt.app.data.remote.llm.LlmServiceFactory
import com.mtt.app.data.remote.openai.OpenAiClient
import com.mtt.app.domain.glossary.GlossaryEngine
import com.mtt.app.domain.glossary.GlossaryEntry
import com.mtt.app.domain.prompt.PromptBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full translation pipeline:
 * preprocess → build prompt → estimate tokens → rate limit → LLM call → validate → extract.
 *
 * On validation failure, retries with progressively smaller batches
 * up to [MAX_RETRIES] times. Emits progress via [BatchResult] flow.
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
        /** Maximum retries per chunk before accepting partial result. */
        const val MAX_RETRIES = 3

        private const val MIN_BATCH_SIZE = 1
    }

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

    /**
     * Execute translation pipeline for a batch of texts.
     *
     * Each text is preprocessed into segments. All non-empty segments
     * are batched together, sent to the LLM, and the results are
     * re-grouped per original text via [TextPreprocessor.postprocess].
     *
     * @param texts  Raw source texts to translate
     * @param config Translation configuration (mode, model, languages, glossary)
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
    //  Orchestration with split-and-retry
    // ──────────────────────────────────────────────

    private suspend fun orchestrate(
        texts: List<String>,
        config: TranslationConfig,
        emitProgress: suspend (BatchResult) -> Unit
    ): BatchResult {
        val queue = ArrayDeque<WorkItem>()
        queue.addLast(WorkItem(chunk = texts, attempt = 0))

        val allItems = mutableListOf<String>()
        var totalTokens = 0
        var completedSize = 0

        while (queue.isNotEmpty()) {
            val (chunk, attempt) = queue.removeFirst()

            emitProgress(
                BatchResult.Progress(
                    batchIndex = 0,
                    completed = completedSize,
                    total = texts.size,
                    stage = "Processing chunk (${chunk.size} items)"
                )
            )

            val chunkResult = processSingleChunk(chunk, config)

            when (chunkResult.outcome) {
                ChunkOutcome.Success -> {
                    allItems.addAll(chunkResult.items)
                    totalTokens += chunkResult.tokensUsed
                    completedSize += chunk.size
                }

                is ChunkOutcome.Invalid -> {
                    val reason = chunkResult.outcome.reason

                    if (chunk.size > MIN_BATCH_SIZE && attempt < MAX_RETRIES) {
                        emitProgress(
                            BatchResult.Retrying(
                                batchIndex = 0,
                                attempt = attempt + 1,
                                reason = reason
                            )
                        )
                        // Split and retry
                        val mid = chunk.size / 2
                        queue.addFirst(WorkItem(chunk.subList(mid, chunk.size), attempt + 1))
                        queue.addFirst(WorkItem(chunk.subList(0, mid), attempt + 1))
                    } else {
                        // Best effort: accept result even if validation failed
                        allItems.addAll(chunkResult.items)
                        totalTokens += chunkResult.tokensUsed
                        completedSize += chunk.size
                    }
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

    private data class WorkItem(
        val chunk: List<String>,
        val attempt: Int
    )

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

        // 3. Build prompt
        val prompt = buildPrompt(
            nonEmptyItems, config, glossarySection, prohibitionSection, ""
        )

        // 4a. Token estimation
        val segmentTexts = nonEmptyItems.map { it.second }
        val estimatedTokens = TokenEstimator.estimateBatch(
            segmentTexts, prompt.systemPrompt, "\n1. "
        )

        // 4b. Context window check
        if (!TokenEstimator.canFitInContext(estimatedTokens, config.model)) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = 0,
                outcome = ChunkOutcome.Invalid(
                    "Batch too large (${estimatedTokens} tokens > 90% of ${config.model.contextWindow})"
                )
            )
        }

        // 5. Rate limit
        try {
            rateLimiter.acquire(estimatedTokens)
        } catch (e: RateLimitException) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = 0,
                outcome = ChunkOutcome.Invalid("Rate limit: ${e.message}")
            )
        }

        // 6. LLM call
        val response = try {
            val llmConfig = LlmRequestConfig(
                messages = listOf(LlmRequestConfig.Message("user", prompt.userMessage)),
                systemPrompt = prompt.systemPrompt,
                model = config.model,
                temperature = config.temperature,
                maxTokens = config.maxTokens
            )
            currentLlmService!!.translate(llmConfig)
        } catch (e: ApiException) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = 0,
                outcome = ChunkOutcome.Invalid("API error (${e.code}): ${e.message}")
            )
        } catch (e: NetworkException) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = 0,
                outcome = ChunkOutcome.Invalid("Network error: ${e.message}")
            )
        }

        // 7. Validate raw response
        val validation = ResponseChecker.validateResponse(
            response.content,
            nonEmptyItems.size
        )

        if (validation != ValidationResult.Valid) {
            return ChunkResult(
                items = emptyList(),
                tokensUsed = response.tokensUsed,
                outcome = ChunkOutcome.Invalid(
                    "Response validation failed: $validation"
                )
            )
        }

        // 8. Extract translations
        val extractionResult = ResponseExtractor.parse(response.content)
        val extractedSegments = when (extractionResult) {
            is ExtractionResult.Success -> extractionResult.translations
            is ExtractionResult.Error -> {
                return ChunkResult(
                    items = emptyList(),
                    tokensUsed = response.tokensUsed,
                    outcome = ChunkOutcome.Invalid(
                        "Response extraction failed: ${extractionResult.message}"
                    )
                )
            }
        }

        // 9. Rejoin per original text using postprocess
        val translatedItems = rebuildTexts(textSegmentsList, extractedSegments)

        return ChunkResult(
            items = translatedItems,
            tokensUsed = response.tokensUsed,
            outcome = ChunkOutcome.Success
        )
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
