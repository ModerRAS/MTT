package com.mtt.app.domain.usecase

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.ParseException
import com.mtt.app.core.error.Result
import com.mtt.app.core.error.TranslationException
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * AI-powered term extraction from source texts.
 *
 * Splits source texts into batches of [MAX_ITEMS_PER_CHUNK], sends each to the LLM
 * with a dedicated extraction prompt, parses the JSON response, and returns a
 * deduplicated list of [ExtractedTerm]s.
 *
 * Uses the existing [LlmServiceFactory] infrastructure and [RateLimiter] for
 * RPM/TPM management.  The extraction prompt is independent from [PromptBuilder].
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
        /** Maximum number of source texts per LLM call. */
        const val MAX_ITEMS_PER_CHUNK = 50

        /** System prompt for AI term extraction. */
        private val EXTRACTION_SYSTEM_PROMPT = buildString {
            append("你是一个术语提取专家。从以下翻译源文本中提取所有专有名词、人名、地名和技术术语。\n")
            append("输出 JSON 数组：[{\"sourceTerm\": \"原文术语\", \"suggestedTarget\": \"建议译文\", \"category\": \"person/place/tech/other\"}]\n")
            append("规则：\n")
            append("1. 只提取真正的专有名词/术语，不要提取普通词汇\n")
            append("2. category 只能使用 person、place、tech、other 之一\n")
            append("3. suggestedTarget 提供建议的目标语言翻译，如果无法确定可留空字符串\n")
            append("4. 不要输出 JSON 数组之外的任何文字\n")
        }

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    /**
     * Extract terminology from the given source texts.
     *
     * @param sourceTexts Map of text-id → text-content pairs
     * @param sourceLang  Source language (e.g., "日语", "英语")
     * @return [Result.Success] with deduplicated terms, or [Result.Failure] on error
     */
    suspend fun extractTerms(
        sourceTexts: Map<String, String>,
        sourceLang: String
    ): Result<List<ExtractedTerm>> {
        if (sourceTexts.isEmpty()) {
            return Result.success(emptyList())
        }

        // 1. Load model from settings
        val modelInfo = loadModelFromSettings()

        // 2. Create provider-specific LlmService
        val llmService = createLlmService(modelInfo.provider)

        // 3. Chunk source texts into batches of MAX_ITEMS_PER_CHUNK
        val entries = sourceTexts.entries.toList()
        val chunks = entries.chunked(MAX_ITEMS_PER_CHUNK)

        // 4. Process each chunk
        val allTerms = mutableListOf<ExtractedTerm>()
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val chunkResult = processChunk(
                chunk = chunk,
                sourceLang = sourceLang,
                modelInfo = modelInfo,
                llmService = llmService,
                chunkIndex = chunkIndex,
                totalChunks = chunks.size
            )
            when (chunkResult) {
                is Result.Success -> allTerms.addAll(chunkResult.data)
                is Result.Failure -> {
                    // If we have partial results from previous chunks, return them
                    // as best-effort instead of losing everything
                    if (allTerms.isNotEmpty()) {
                        val unique = deduplicateAndFilter(allTerms)
                        return Result.success(unique)
                    }
                    return chunkResult
                }
            }
        }

        // 5. Deduplicate and filter empty
        val uniqueTerms = deduplicateAndFilter(allTerms)
        return Result.success(uniqueTerms)
    }

    // ──────────────────────────────────────────────
    //  Per-chunk processing
    // ──────────────────────────────────────────────

    private suspend fun processChunk(
        chunk: List<Map.Entry<String, String>>,
        sourceLang: String,
        modelInfo: ModelInfo,
        llmService: LlmService,
        chunkIndex: Int,
        totalChunks: Int
    ): Result<List<ExtractedTerm>> {
        try {
            // Build user message with numbered texts
            val userMessage = buildUserMessage(chunk, sourceLang, chunkIndex, totalChunks)

            // Estimate tokens
            val estimatedTokens = TokenEstimator.estimate(userMessage) +
                    TokenEstimator.estimate(EXTRACTION_SYSTEM_PROMPT) +
                    100 // overhead

            // Context window check
            if (!TokenEstimator.canFitInContext(estimatedTokens, modelInfo)) {
                return Result.failure(
                    TranslationException(
                        "Chunk ${chunkIndex + 1} too large: $estimatedTokens tokens exceed context window"
                    )
                )
            }

            // Rate limit
            try {
                rateLimiter.acquire(estimatedTokens)
            } catch (e: RateLimitException) {
                return Result.failure(
                    TranslationException("Rate limit exceeded: ${e.message}")
                )
            }

            // LLM call
            val response = llmService.translate(
                LlmRequestConfig(
                    messages = listOf(LlmRequestConfig.Message("user", userMessage)),
                    systemPrompt = EXTRACTION_SYSTEM_PROMPT,
                    model = modelInfo,
                    temperature = 0.1f, // Low temperature for extraction consistency
                    maxTokens = 4096
                )
            )

            // Parse JSON response
            return parseExtractionResponse(response.content)
        } catch (e: ApiException) {
            return Result.failure(e)
        } catch (e: NetworkException) {
            return Result.failure(e)
        } catch (e: ParseException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(
                TranslationException("Term extraction failed: ${e.message}")
            )
        }
    }

    /**
     * Build the user message containing numbered source texts.
     */
    private fun buildUserMessage(
        chunk: List<Map.Entry<String, String>>,
        sourceLang: String,
        chunkIndex: Int,
        totalChunks: Int
    ): String {
        val sb = StringBuilder()
        sb.append("源语言：$sourceLang\n")
        if (totalChunks > 1) {
            sb.append("批次：${chunkIndex + 1}/$totalChunks\n")
        }
        sb.append("待提取文本：\n")
        for ((i, entry) in chunk.withIndex()) {
            sb.append("${i + 1}. ${entry.value}\n")
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
