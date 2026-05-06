package com.mtt.app.domain.usecase

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.ParseException
import com.mtt.app.core.error.Result
import com.mtt.app.core.error.TranslationException
import com.mtt.app.data.llm.FrequencyAnalyzer
import com.mtt.app.data.llm.FrequencyCandidate
import com.mtt.app.data.llm.RateLimitException
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.llm.TokenEstimator
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.remote.anthropic.AnthropicClient
import com.mtt.app.data.remote.llm.LlmService
import com.mtt.app.data.remote.llm.LlmServiceFactory
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.remote.openai.OpenAiClient
import com.mtt.app.data.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * AI-powered term extraction from source texts.
 *
 * Two-phase extraction:
 * 1. **[Phase 1]** Frequency analysis — locally scan all source texts for recurring
 *    character sequences to identify candidate glossary terms. This is fast and
 *    requires zero API calls.
 * 2. **[Phase 2]** LLM validation — send the candidates to the LLM for translation
 *    and categorization. Much more efficient than scanning 42K entries chunk-by-chunk.
 *
 * Uses the existing [LlmServiceFactory] infrastructure and [RateLimiter] for
 * RPM/TPM management. The extraction prompt is independent from [PromptBuilder].
 *
 * @param secureStorage  For reading the active model configuration and API keys
 * @param okHttpClient   Base OkHttpClient (from DI) for constructing provider clients
 * @param rateLimiter    Rate limiter for API quota management
 */
class ExtractTermsUseCase @Inject constructor(
    private val secureStorage: SecureStorage,
    private val okHttpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) {

    companion object {
        /** Maximum number of candidates per LLM validation call. */
        const val MAX_CANDIDATES_PER_CALL = 300

        /** System prompt for AI term validation (Phase 2). */
        private val VALIDATION_SYSTEM_PROMPT = buildString {
            append("你是一个术语验证专家。以下是一批由频率分析发现的候选术语。\n")
            append("请判断每个候选词是否是真正的专有名词、人名、地名或技术术语。\n")
            append("输出 JSON 数组：[{\"sourceTerm\": \"原文术语\", \"suggestedTarget\": \"建议译文\", \"category\": \"person/place/tech/other\"}]\n")
            append("规则：\n")
            append("1. 只保留真正的专有名词/术语，丢弃普通词汇或无关内容\n")
            append("2. category 只能使用 person、place、tech、other 之一\n")
            append("3. suggestedTarget 提供建议的目标语言翻译，如果无法确定可留空字符串\n")
            append("4. 不要输出 JSON 数组之外的任何文字\n")
        }

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private const val TARGET_TOKENS_PER_CALL = 4096
        private const val LLM_TEMPERATURE = 0.1f
    }

    /**
     * Extract terminology from the given source texts.
     *
     * Two-phase approach with per-chunk progress reporting:
     * - Phase 1: Local frequency analysis (1 step, fast, no API calls)
     * - Phase 2: LLM validation of candidates (N steps, one per batch)
     *
     * Progress: (completed, total) where total = 1 (Phase 1) + candidateChunks.size (Phase 2)
     * Example with 2 candidate chunks: 0/3 → 1/3 → 2/3 → 3/3
     *
     * @param sourceTexts Map of text-id → text-content pairs
     * @param sourceLang  Source language (e.g., "日语", "英语")
     * @param onProgress  Callback invoked with (completedSteps, totalSteps)
     * @return [Result.Success] with deduplicated terms, or [Result.Failure] on error
     */
    suspend fun extractTerms(
        sourceTexts: Map<String, String>,
        sourceLang: String,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<ExtractedTerm>> {
        if (sourceTexts.isEmpty()) {
            return Result.success(emptyList())
        }

        // ── Phase 1: Frequency analysis (CPU-bound, run on Default dispatcher) ──
        val limitedCandidates = try {
            withContext(Dispatchers.Default) {
                val textValues = sourceTexts.values
                val candidates = FrequencyAnalyzer.extractCandidates(textValues)
                FrequencyAnalyzer.limitCandidates(candidates)
            }
        } catch (e: Exception) {
            return Result.failure(
                TranslationException("频率分析失败: ${e.message}")
            )
        }

        if (limitedCandidates.isEmpty()) {
            onProgress(1, 1)
            return Result.success(emptyList())
        }

        // Chunk candidates: total progress = 1 (freq analysis) + N (LLM chunks)
        val candidateChunks = limitedCandidates.chunked(MAX_CANDIDATES_PER_CALL)
        val totalProgressSteps = 1 + candidateChunks.size

        onProgress(1, totalProgressSteps) // Phase 1 done

        // ── Phase 2: LLM validation ──────────────────
        val modelInfo = loadModelFromSettings()
        val llmService = createLlmService(modelInfo.provider)

        val allTerms = mutableListOf<ExtractedTerm>()

        for ((chunkIndex, chunk) in candidateChunks.withIndex()) {
            val chunkResult = validateCandidateChunk(
                candidates = chunk,
                sourceLang = sourceLang,
                modelInfo = modelInfo,
                llmService = llmService,
                chunkIndex = chunkIndex,
                totalChunks = candidateChunks.size
            )
            when (chunkResult) {
                is Result.Success -> allTerms.addAll(chunkResult.data)
                is Result.Failure -> {
                    if (allTerms.isNotEmpty()) {
                        val unique = deduplicateAndFilter(allTerms)
                        return Result.success(unique)
                    }
                    return chunkResult
                }
            }
            onProgress(1 + chunkIndex + 1, totalProgressSteps)
        }

        val uniqueTerms = deduplicateAndFilter(allTerms)
        return Result.success(uniqueTerms)
    }

    // ──────────────────────────────────────────────
    //  Candidate validation (Phase 2)
    // ──────────────────────────────────────────────

    private suspend fun validateCandidateChunk(
        candidates: List<FrequencyCandidate>,
        sourceLang: String,
        modelInfo: ModelInfo,
        llmService: LlmService,
        chunkIndex: Int,
        totalChunks: Int
    ): Result<List<ExtractedTerm>> {
        try {
            val userMessage = buildCandidateMessage(candidates, sourceLang, chunkIndex, totalChunks)

            val estimatedTokens = TokenEstimator.estimate(userMessage) +
                    TokenEstimator.estimate(VALIDATION_SYSTEM_PROMPT) +
                    100

            if (!TokenEstimator.canFitInContext(estimatedTokens, modelInfo)) {
                return Result.failure(
                    TranslationException(
                        "Candidate chunk ${chunkIndex + 1} too large: $estimatedTokens tokens exceed context window"
                    )
                )
            }

            try {
                rateLimiter.acquire(estimatedTokens)
            } catch (e: RateLimitException) {
                return Result.failure(
                    TranslationException("Rate limit exceeded: ${e.message}")
                )
            }

            val response = llmService.translate(
                LlmRequestConfig(
                    messages = listOf(LlmRequestConfig.Message("user", userMessage)),
                    systemPrompt = VALIDATION_SYSTEM_PROMPT,
                    model = modelInfo,
                    temperature = LLM_TEMPERATURE,
                    maxTokens = TARGET_TOKENS_PER_CALL
                )
            )

            return parseExtractionResponse(response.content)
        } catch (e: ApiException) {
            return Result.failure(e)
        } catch (e: NetworkException) {
            return Result.failure(e)
        } catch (e: ParseException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(
                TranslationException("Term validation failed: ${e.message}")
            )
        }
    }

    /**
     * Build the user message containing candidate terms for LLM validation.
     */
    private fun buildCandidateMessage(
        candidates: List<FrequencyCandidate>,
        sourceLang: String,
        chunkIndex: Int,
        totalChunks: Int
    ): String {
        val sb = StringBuilder()
        sb.append("源语言：$sourceLang\n")
        if (totalChunks > 1) {
            sb.append("批次：${chunkIndex + 1}/$totalChunks\n")
        }
        sb.append("以下是由词频分析发现的候选术语（数字表示出现次数）：\n\n")
        for ((i, candidate) in candidates.withIndex()) {
            sb.append("${i + 1}. ${candidate.term} (出现 ${candidate.frequency} 次)\n")
        }
        return sb.toString()
    }

    /**
     * Parse the LLM JSON response into a list of [ExtractedTerm]s.
     *
     * Handles common LLM output patterns: markdown code fences, leading/trailing text.
     */
    private fun parseExtractionResponse(rawContent: String): Result<List<ExtractedTerm>> {
        if (rawContent.isBlank()) {
            return Result.success(emptyList())
        }

        return try {
            val jsonArray = extractJsonArray(rawContent)
            val terms: List<ExtractedTerm> = json.decodeFromString(jsonArray)
            Result.success(terms)
        } catch (e: Exception) {
            Result.failure(
                ParseException("Failed to parse LLM extraction response: ${e.message}")
            )
        }
    }

    /**
     * Extract the JSON array from the LLM response, handling markdown code fences
     * and extra text before/after the array.
     */
    private fun extractJsonArray(content: String): String {
        var cleaned = content.trim()

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val fenceMatch = fenceRegex.find(cleaned)
        if (fenceMatch != null) {
            cleaned = fenceMatch.groupValues[1].trim()
        }

        // Find the JSON array boundaries
        val startIdx = cleaned.indexOf('[')
        val endIdx = cleaned.lastIndexOf(']')

        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            cleaned = cleaned.substring(startIdx, endIdx + 1)
        }

        return cleaned
    }

    /**
     * Deduplicate terms by lowercase source term and filter out blanks.
     */
    private fun deduplicateAndFilter(terms: List<ExtractedTerm>): List<ExtractedTerm> {
        return terms
            .filter { it.sourceTerm.isNotBlank() }
            .distinctBy { it.sourceTerm.lowercase().trim() }
    }

    // ──────────────────────────────────────────────
    //  Model loading & LLM service creation
    // ──────────────────────────────────────────────

    /**
     * Load the active model configuration from [SecureStorage].
     *
     * Follows the same priority logic as [com.mtt.app.ui.translation.TranslationViewModel.loadModelFromSettings]:
     * - If only Anthropic key is configured → use Anthropic
     * - Otherwise (OpenAI configured, or both) → use OpenAI
     */
    private fun loadModelFromSettings(): ModelInfo {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC)
        val openAiBaseUrl = secureStorage.getValue(SecureStorage.KEY_OPENAI_BASE_URL)
            ?: LlmProvider.OpenAI("", "").baseUrl
        val anthropicBaseUrl = secureStorage.getValue(SecureStorage.KEY_ANTHROPIC_BASE_URL)
            ?: LlmProvider.Anthropic("", "").baseUrl

        return if (anthropicKey?.isNotBlank() == true && openAiKey?.isNotBlank() != true) {
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_ANTHROPIC_MODEL)
                ?: ModelRegistry.defaultAnthropicModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                baseModel.copy(provider = LlmProvider.Anthropic(anthropicKey, anthropicBaseUrl))
            } else {
                ModelInfo(
                    modelId = modelId,
                    displayName = modelId,
                    contextWindow = 200000,
                    provider = LlmProvider.Anthropic(anthropicKey, anthropicBaseUrl)
                )
            }
        } else {
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_OPENAI_MODEL)
                ?: ModelRegistry.defaultOpenAiModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                baseModel.copy(provider = LlmProvider.OpenAI(openAiKey ?: "", openAiBaseUrl))
            } else {
                ModelInfo(
                    modelId = modelId,
                    displayName = modelId,
                    contextWindow = 128000,
                    provider = LlmProvider.OpenAI(openAiKey ?: "", openAiBaseUrl)
                )
            }
        }
    }

    /**
     * Create a provider-specific [LlmService] from the user's config.
     *
     * Uses the same dummy-client pattern as [com.mtt.app.domain.pipeline.TranslationExecutor.createLlmService].
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
}
