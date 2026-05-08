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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * AI-powered term extraction from source texts — AiNiee-style two-stage pipeline.
 *
 * **Stage 1 (Direct LLM Extraction):** Source texts are split into token-bounded
 * chunks. Each chunk is sent to the LLM with a few-shot prompt asking it to
 * extract three categories: characters (角色), terms (术语), and non_translate (禁翻项).
 * Returns structured JSON with source/recommended_translation/category/note per item.
 *
 * **Stage 2 (AI Dedup & Merge):** All Stage-1 candidates are grouped by source term.
 * Short variants are merged under long forms. Batches are sent to the LLM for
 * conflict resolution (character vs. term classification) and final dedup.
 *
 * This replaces the old frequency-analysis + tool-calling approach which produced
 * garbage fragments for CJK text (especially Japanese, where character n-grams
 * don't correspond to meaningful words).
 *
 * Reference: AiNiee AnalysisTask.py (兩階段分析流水線)
 */
class ExtractTermsUseCase @Inject constructor(
    private val secureStorage: SecureStorage,
    private val okHttpClient: OkHttpClient,
    private val rateLimiter: RateLimiter
) {
    /** Source alias map: short variants → canonical long form (AiNiee's grouped_stage_two_source_aliases). */
    private val stage2SourceAliases = mutableMapOf<String, String>()

    /** Accumulated token counts for the current extraction run. Reset per call in [extractTerms]. */
    @PublishedApi internal var accumulatedInputTokens = 0
    @PublishedApi internal var accumulatedOutputTokens = 0

    companion object {
        /** Max estimated tokens per Stage-1 text chunk (reduced to leave room for JSON output). */
        const val CHUNK_TOKEN_LIMIT = 6000

        /** Max estimated tokens per Stage-2 LLM dedup call (matching AiNiee's REDUCE_BATCH_TOKEN_LIMIT = 10000). */
        const val STAGE2_BATCH_TOKEN_LIMIT = 10000

        /** Characters considered as punctuation for non_translate filtering (AiNiee COMMON_PUNCTUATION_CHARS). */
        private val PUNCTUATION_CHARS = setOf(
            '.', ',', '!', '?', ';', ':', '\'', '"', '-', '_', '=', '+', '~', '`', '^', '…', '—',
            '、', '，', '。', '！', '？', '；', '：', '‘', '’', '“', '”', '(', ')', '（', '）',
            '[', ']', '【', '】', '{', '}', '《', '》', '<', '>', '「', '」', '『', '』', '〈', '〉',
            '〔', '〕', '﹝', '﹞', '·', '•', '/', '\\', '|', ' ', '\t', '\n', '\r'
        )

        const val TARGET_TOKENS_PER_CALL = 8192
        const val LLM_TEMPERATURE = 0.1f
        const val MAX_RETRIES = 2

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    /**
     * Extract terminology from source texts using a two-stage LLM pipeline.
     *
     * Progress: (completed, total) where total = textChunks.size + mergeBatches.size.
     * Stage 1 yields one step per text chunk; Stage 2 yields one step per merge batch.
     *
     * @param sourceTexts Map of text-id → text-content pairs
     * @param sourceLang  Source language (e.g., "日语", "英语")
     * @param onProgress  Callback invoked with (completedSteps, totalSteps)
     * @return [Result.Success] with deduplicated items, or [Result.Failure] on error
     */
    suspend fun extractTerms(
        sourceTexts: Map<String, String>,
        sourceLang: String,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<ExtractedTerm>> {
        if (sourceTexts.isEmpty()) {
            return Result.success(emptyList())
        }

        // Reset accumulated token counters
        accumulatedInputTokens = 0
        accumulatedOutputTokens = 0

        val textValues = sourceTexts.values.toList()
        val modelInfo = loadModelFromSettings()
        val llmService = createLlmService(modelInfo.provider)

        // ── Stage 1: Direct LLM extraction from text chunks ──────────────
        val textChunks = buildTextChunks(textValues)
        val totalSteps = textChunks.size // Stage 2 steps added after Stage 1

        val stage1AllItems = mutableListOf<ExtractedTerm>()

        for ((chunkIndex, chunkText) in textChunks.withIndex()) {
            val chunkResult = processStage1Chunk(
                chunkText = chunkText,
                sourceLang = sourceLang,
                modelInfo = modelInfo,
                llmService = llmService,
                chunkIndex = chunkIndex,
                totalChunks = textChunks.size
            )
            when (chunkResult) {
                is Result.Success -> stage1AllItems.addAll(chunkResult.data)
                is Result.Failure -> {
                    // Partial results are better than nothing
                    if (stage1AllItems.isNotEmpty()) break
                    return chunkResult
                }
            }
            onProgress(chunkIndex + 1, totalSteps)
        }

        if (stage1AllItems.isEmpty()) {
            return Result.success(emptyList())
        }

        // ── Stage 2: AI dedup & merge ────────────────────────────────────
        val mergedBatches = buildStage2Batches(stage1AllItems)
        val adjustedTotal = textChunks.size + mergedBatches.size
        val stage2Results = mutableListOf<ExtractedTerm>()

        for ((batchIndex, batch) in mergedBatches.withIndex()) {
            val batchResult = processStage2Batch(
                batch = batch,
                modelInfo = modelInfo,
                llmService = llmService,
                batchIndex = batchIndex,
                totalBatches = mergedBatches.size
            )
            when (batchResult) {
                is Result.Success -> stage2Results.addAll(batchResult.data)
                is Result.Failure -> {
                    if (stage2Results.isNotEmpty()) break
                    // Fallback: use Stage 1 results directly
                    return Result.success(deduplicateAndFilter(stage1AllItems))
                }
            }
            onProgress(textChunks.size + batchIndex + 1, adjustedTotal)
        }

        // ── Finalize: rescue AI-discarded items + merge non_translate ────
        // AiNiee-style _finalize_results: (1) adopt Stage 2 AI results,
        // (2) rescue groups the LLM omitted via weighted heuristic,
        // (3) clean and merge non_translate items from Stage 1.
        val allStage2Groups = mergedBatches.flatten()
        val mergedItems = if (stage2Results.isNotEmpty()) {
            val finalized = finalizeResults(stage2Results, allStage2Groups, stage1AllItems)
            val cleanedNonTranslate = mergeNonTranslateItems(stage1AllItems)
            finalized.toMutableList().apply { addAll(cleanedNonTranslate) }
        } else {
            stage1AllItems.toMutableList()
        }
        return Result.success(deduplicateAndFilter(mergedItems))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Stage 1: Direct LLM Extraction
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build text chunks bounded by estimated token count.
     * Each chunk is a newline-joined block of source texts.
     */
    private fun buildTextChunks(texts: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        val currentBuilder = StringBuilder()
        var currentTokens = 0

        for (text in texts) {
            if (text.isBlank()) continue
            val textTokens = TokenEstimator.estimate(text) + 1 // +1 for newline
            if (currentTokens + textTokens > CHUNK_TOKEN_LIMIT && currentBuilder.isNotEmpty()) {
                chunks.add(currentBuilder.toString())
                currentBuilder.clear()
                currentTokens = 0
            }
            if (currentBuilder.isNotEmpty()) currentBuilder.append('\n')
            currentBuilder.append(text)
            currentTokens += textTokens
        }
        if (currentBuilder.isNotEmpty()) {
            chunks.add(currentBuilder.toString())
        }
        return chunks
    }

    /**
     * Send a text chunk to the LLM for Stage-1 extraction.
     * Uses a few-shot prompt inspired by AiNiee AnalysisTask.
     */
    private suspend fun processStage1Chunk(
        chunkText: String,
        sourceLang: String,
        modelInfo: ModelInfo,
        llmService: LlmService,
        chunkIndex: Int,
        totalChunks: Int
    ): Result<List<ExtractedTerm>> {
        try {
            val messages = buildStage1Messages(chunkText, sourceLang, chunkIndex, totalChunks)
            val systemPrompt = buildStage1SystemPrompt()

            val estimatedTokens = TokenEstimator.estimate(chunkText) +
                    TokenEstimator.estimate(systemPrompt) +
                    500 // overhead for few-shot examples + response

            if (!TokenEstimator.canFitInContext(estimatedTokens, modelInfo)) {
                return Result.failure(
                    TranslationException(
                        "Chunk ${chunkIndex + 1} too large: $estimatedTokens tokens exceed context window"
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
                    messages = messages,
                    systemPrompt = systemPrompt,
                    model = modelInfo,
                    temperature = LLM_TEMPERATURE,
                    maxTokens = TARGET_TOKENS_PER_CALL
                    // No toolChoice — uses free-text JSON output
                )
            )

            // Accumulate token usage for UI display
            accumulatedInputTokens += response.inputTokens
            accumulatedOutputTokens += response.outputTokens

            return parseStage1Response(response.content)
        } catch (e: ApiException) {
            return Result.failure(e)
        } catch (e: NetworkException) {
            return Result.failure(e)
        } catch (e: ParseException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(
                TranslationException("Stage 1 extraction failed: ${e.message}")
            )
        }
    }

    /** Build the Stage-1 system prompt modeled after AiNiee AnalysisTask. */
    private fun buildStage1SystemPrompt(): String = buildString {
        append("你是一个专业的游戏与本地化文本分析专家。你的唯一任务是从给定的文本中提取出：角色名、专有名词（术语）以及不需要翻译的代码/标记。\n")
        append("【严格执行以下规则】\n")
        append("1. 原样提取：提取的 `source` 必须与原文一字不差，绝对不要修改大小写或标点。\n")
        append("2. 拒绝脑补：只提取文本中实际出现的实体，不要联想或创造。\n")
        append("3. 宁缺毋滥：对于普通词汇（如\"苹果\"、\"跑\"、\"明天\"），不要提取。如果没有值得提取的内容，返回空列表。\n")
        append("4. 分类规范：\n")
        append("   - characters(角色): 文本中出现的具体人物、怪物、神明等名字。gender 建议分类：男性/女性/其他\n")
        append("   - terms(术语): 身份称谓、地名、组织、物品名、技能名、种族名、独特概念等。category_path 建议分类：身份/物品/组织/地名/技能/种族/其他\n")
        append("   - non_translate(不翻译项): 必须保留的机器代码，如HTML标签(<b>)、占位符(%s)、变量({{name}})。category 建议分类：标签/变量/占位符/标记符/转义控制符/资源标识/数值公式/其他\n")
        append("【输出格式】\n")
        append("必须输出合法的 JSON 代码块，严格遵守以下结构：\n")
        append("```json\n")
        append("{\n")
        append("  \"characters\": [{\"source\": \"原文\", \"recommended_translation\": \"推荐译名\", \"gender\": \"\", \"note\": \"\"}],\n")
        append("  \"terms\": [{\"source\": \"原文\", \"recommended_translation\": \"推荐译名\", \"category_path\": \"\", \"note\": \"\"}],\n")
        append("  \"non_translate\": [{\"marker\": \"代码或标记\", \"category\": \"\", \"note\": \"\"}]\n")
        append("}\n")
        append("```")
    }

    /** Build the Stage-1 messages with few-shot examples (AiNiee-style). */
    private fun buildStage1Messages(
        chunkText: String,
        sourceLang: String,
        chunkIndex: Int,
        totalChunks: Int
    ): List<LlmRequestConfig.Message> {
        val languageRequirement = "所有推荐译名、分类、备注都必须写成简体中文。\n"

        // Few-shot example: user
        val fakeUser = buildString {
            append("请分析以下文本并提取信息，$languageRequirement")
            append("---\n")
            append("露娜小姐：请携带[圣剑]前往星门集合。\n")
            append("精灵族战士即将施放月光斩。\n")
            append("系统提示：欢迎回来，{{player_name}}！<br>请注意<color=red>HP</color>的变化。\n")
            append("---\n")
            append("请输出 JSON 提取结果。")
        }

        // Few-shot example: assistant
        val fakeAssistant = buildString {
            append("```json\n")
            append("{\n")
            append("  \"characters\": [\n")
            append("    {\"source\": \"露娜小姐\", \"recommended_translation\": \"露娜小姐\", \"gender\": \"女性\", \"note\": \"NPC称呼\"}\n")
            append("  ],\n")
            append("  \"terms\": [\n")
            append("    {\"source\": \"圣剑\", \"recommended_translation\": \"圣剑\", \"category_path\": \"物品\", \"note\": \"武器名称\"},\n")
            append("    {\"source\": \"星门\", \"recommended_translation\": \"星门\", \"category_path\": \"地名\", \"note\": \"地点\"},\n")
            append("    {\"source\": \"月光斩\", \"recommended_translation\": \"月光斩\", \"category_path\": \"技能\", \"note\": \"招式名称\"},\n")
            append("    {\"source\": \"精灵族\", \"recommended_translation\": \"精灵族\", \"category_path\": \"种族\", \"note\": \"种族名称\"},\n")
            append("    {\"source\": \"HP\", \"recommended_translation\": \"HP\", \"category_path\": \"其他\", \"note\": \"生命值\"}\n")
            append("  ],\n")
            append("  \"non_translate\": [\n")
            append("    {\"marker\": \"{{player_name}}\", \"category\": \"变量\", \"note\": \"玩家名变量\"},\n")
            append("    {\"marker\": \"<br>\", \"category\": \"标签\", \"note\": \"换行符\"},\n")
            append("    {\"marker\": \"<color=red>\", \"category\": \"标签\", \"note\": \"颜色富文本标签\"},\n")
            append("    {\"marker\": \"</color>\", \"category\": \"标签\", \"note\": \"颜色富文本标签闭合\"}\n")
            append("  ]\n")
            append("}\n")
            append("```")
        }

        val headerInfo = if (totalChunks > 1) {
            "（第 ${chunkIndex + 1}/$totalChunks 批）"
        } else ""

        val userPrompt = buildString {
            append("请分析以下文本并提取信息，$languageRequirement")
            append("---\n")
            append(chunkText)
            append("\n---\n")
            append("请输出 JSON 提取结果$headerInfo。")
        }

        return listOf(
            LlmRequestConfig.Message("user", fakeUser),
            LlmRequestConfig.Message("assistant", fakeAssistant),
            LlmRequestConfig.Message("user", userPrompt)
        )
    }

    /**
     * Parse Stage-1 LLM response: extract JSON from code blocks,
     * then convert characters/terms/non_translate arrays to [ExtractedTerm] list.
     */
    private fun parseStage1Response(content: String): Result<List<ExtractedTerm>> {
        if (content.isBlank()) return Result.success(emptyList())

        val jsonObj = try {
            val cleaned = extractJsonObject(content)
            if (cleaned.isBlank()) return Result.success(emptyList())
            JSONObject(cleaned)
        } catch (e: Exception) {
            return Result.failure(
                ParseException("Failed to parse Stage-1 JSON: ${e.message}")
            )
        }

        val items = mutableListOf<ExtractedTerm>()

        // Parse characters
        val charsArray = jsonObj.optJSONArray("characters")
        if (charsArray != null) {
            for (i in 0 until charsArray.length()) {
                val obj = charsArray.optJSONObject(i) ?: continue
                val source = obj.optString("source", "").trim()
                if (source.isBlank()) continue
                items.add(
                    ExtractedTerm(
                        sourceTerm = source,
                        suggestedTarget = obj.optString("recommended_translation", "").trim(),
                        type = ExtractedTerm.TYPE_CHARACTER,
                        category = obj.optString("gender", "").trim(),
                        explanation = obj.optString("note", "").trim()
                    )
                )
            }
        }

        // Parse terms
        val termsArray = jsonObj.optJSONArray("terms")
        if (termsArray != null) {
            for (i in 0 until termsArray.length()) {
                val obj = termsArray.optJSONObject(i) ?: continue
                val source = obj.optString("source", "").trim()
                if (source.isBlank()) continue
                items.add(
                    ExtractedTerm(
                        sourceTerm = source,
                        suggestedTarget = obj.optString("recommended_translation", "").trim(),
                        type = ExtractedTerm.TYPE_TERM,
                        category = obj.optString("category_path", "").trim(),
                        explanation = obj.optString("note", "").trim()
                    )
                )
            }
        }

        // Parse non_translate
        val ntArray = jsonObj.optJSONArray("non_translate")
        if (ntArray != null) {
            for (i in 0 until ntArray.length()) {
                val obj = ntArray.optJSONObject(i) ?: continue
                val marker = obj.optString("marker", "").trim()
                if (marker.isBlank()) continue
                items.add(
                    ExtractedTerm(
                        sourceTerm = marker,
                        suggestedTarget = "", // non_translate items have no translation
                        type = ExtractedTerm.TYPE_NON_TRANSLATE,
                        category = obj.optString("category", "").trim(),
                        explanation = obj.optString("note", "").trim()
                    )
                )
            }
        }

        return Result.success(items)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Stage 2: AI Dedup & Merge
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build Stage-2 batches: group all items by source term, merge short variants
     * under long forms. Returns a list of batches, each containing grouped candidates.
     */
    private fun buildStage2Batches(items: List<ExtractedTerm>): List<List<Stage2Group>> {
        // Only group character and term items — non_translate is finalized in Stage 1
        // (matching AiNiee's _prepare_reduction_batches which only processes characters & terms)
        val rawGroups = mutableMapOf<String, MutableList<ExtractedTerm>>()
        for (item in items) {
            if (item.type == ExtractedTerm.TYPE_NON_TRANSLATE) continue
            val key = item.sourceTerm.trim()
            if (key.isBlank()) continue
            rawGroups.getOrPut(key) { mutableListOf() }.add(item)
        }

        // Sort: longest source first (long terms are more specific)
        val sortedSources = rawGroups.keys.sortedByDescending { it.length }

        // Merge short variants under long terms (e.g., "亚瑟" → "亚瑟王")
        val merged = mutableMapOf<String, Stage2Group>()
        val consumed = mutableSetOf<String>()

        for (source in sortedSources) {
            if (source in consumed) continue
            val candidates = rawGroups[source] ?: continue
            val group = Stage2Group(
                source = source,
                allVariants = mutableListOf(source),
                candidates = candidates.toMutableList()
            )
            merged[source] = group
            consumed.add(source)

            // Find short variants contained in this long term
            for (other in sortedSources) {
                if (other in consumed || other.length >= source.length) continue
                if (source.contains(other)) {
                    group.allVariants.add(other)
                    rawGroups[other]?.let { group.candidates.addAll(it) }
                    consumed.add(other)
                }
            }

            // Populate source alias map (short → canonical long form, AiNiee-style)
            stage2SourceAliases[source] = source // canonical maps to itself
            for (variant in group.allVariants) {
                if (variant != source) {
                    stage2SourceAliases[variant] = source
                }
            }
        }

        // Sort groups: largest first
        val sortedGroups = merged.values.sortedByDescending { it.source.length }

        // Token-based batching (matching AiNiee's REDUCE_BATCH_TOKEN_LIMIT)
        val batches = mutableListOf<List<Stage2Group>>()
        val currentBatch = mutableListOf<Stage2Group>()
        var currentTokens = 0

        for (group in sortedGroups) {
            val groupTokens = estimateStage2GroupTokens(group)
            if (currentBatch.isNotEmpty() && currentTokens + groupTokens > STAGE2_BATCH_TOKEN_LIMIT) {
                batches.add(currentBatch.toList())
                currentBatch.clear()
                currentTokens = 0
            }
            currentBatch.add(group)
            currentTokens += groupTokens
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch.toList())
        }

        return batches
    }

    /**
     * Send a batch of candidate groups to the LLM for dedup/merge (Stage 2).
     * Modeled after AiNiee's second-stage "AI裁决" (AI arbitration).
     */
    private suspend fun processStage2Batch(
        batch: List<Stage2Group>,
        modelInfo: ModelInfo,
        llmService: LlmService,
        batchIndex: Int,
        totalBatches: Int
    ): Result<List<ExtractedTerm>> {
        try {
            val systemPrompt = buildStage2SystemPrompt()
            val messages = buildStage2Messages(batch)

            val estimatedMessageTokens = batch.sumOf { group ->
                TokenEstimator.estimate(group.source) +
                    group.candidates.sumOf { TokenEstimator.estimate(it.sourceTerm) }
            }
            val estimatedTokens = estimatedMessageTokens +
                    TokenEstimator.estimate(systemPrompt) +
                    500

            if (!TokenEstimator.canFitInContext(estimatedTokens, modelInfo)) {
                // If too large, use heuristic dedup instead
                return Result.success(heuristicDedupBatch(batch))
            }

            try {
                rateLimiter.acquire(estimatedTokens)
            } catch (e: RateLimitException) {
                // Fallback to heuristic on rate limit
                return Result.success(heuristicDedupBatch(batch))
            }

            val response = llmService.translate(
                LlmRequestConfig(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    model = modelInfo,
                    temperature = LLM_TEMPERATURE,
                    maxTokens = TARGET_TOKENS_PER_CALL
                )
            )

            // Accumulate token usage for UI display
            accumulatedInputTokens += response.inputTokens
            accumulatedOutputTokens += response.outputTokens

            val result = parseStage2Response(response.content)
            if (result is Result.Success && result.data.isNotEmpty()) {
                return result
            }
            // Fallback
            return Result.success(heuristicDedupBatch(batch))
        } catch (e: Exception) {
            return Result.success(heuristicDedupBatch(batch))
        }
    }

    /** Build the Stage-2 system prompt (AI arbitration/merge). */
    private fun buildStage2SystemPrompt(): String = buildString {
        append("你是一个本地化术语库规范化专家。你将收到一组经初步提取的\"候选词组（Group）\"。\n")
        append("有时候相同的词汇会被误判为不同的类型（如既被识别为角色，又被识别为术语）。\n")
        append("你的唯一任务是：综合判定每个 Group，裁决它最终属于\"角色(characters)\"还是\"术语(terms)\"，并提炼出一个最准确的结果。\n")
        append("【严格执行以下规则】\n")
        append("1. 唯一归属：同一个词不能既是角色又是术语，必须二选一。\n")
        append("2. 主键保留：输出的 `source` 必须严格使用传入的主 source，绝不能随意篡改。\n")
        append("3. 丢弃无价值词汇：如果某个 Group 里的词汇看起来是普通词语（如\"今天\"、\"然后\"），请直接忽略，不要输出它。\n")
        append("4. 信息整合：如果推荐译名或备注有多个参考，请合并为你认为最合理的版本。\n")
        append("【输出格式】\n")
        append("必须输出合法的 JSON 代码块，严格遵守以下结构：\n")
        append("```json\n")
        append("{\n")
        append("  \"characters\": [{\"source\": \"主source\", \"recommended_translation\": \"推荐译名\", \"gender\": \"分类属性\", \"note\": \"整合后的备注\"}],\n")
        append("  \"terms\": [{\"source\": \"主source\", \"recommended_translation\": \"推荐译名\", \"category_path\": \"分类属性\", \"note\": \"整合后的备注\"}]\n")
        append("}\n")
        append("```")
    }

    /** Build Stage-2 messages with few-shot example. */
    private fun buildStage2Messages(batch: List<Stage2Group>): List<LlmRequestConfig.Message> {
        // Convert batch to serializable format for the prompt
        val batchJson = buildString {
            append("[\n")
            for ((i, group) in batch.withIndex()) {
                if (i > 0) append(",\n")
                append("  {\n")
                append("    \"source\": \"${group.source}\",\n")
                append("    \"merged_sources\": [")
                append(group.allVariants.joinToString(", ") { "\"${it}\"" })
                append("],\n")
                append("    \"candidates\": [\n")
                for ((j, cand) in group.candidates.withIndex()) {
                    if (j > 0) append(",\n")
                    append("      {\n")
                    val type = when (cand.type) {
                        ExtractedTerm.TYPE_CHARACTER -> "character"
                        ExtractedTerm.TYPE_TERM -> "term"
                        ExtractedTerm.TYPE_NON_TRANSLATE -> "non_translate"
                        else -> "term"
                    }
                    append("        \"type\": \"$type\",\n")
                    append("        \"recommended_translation\": \"${cand.suggestedTarget}\",\n")
                    if (type == "character") {
                        append("        \"gender\": \"${cand.category}\",\n")
                    } else {
                        append("        \"category_path\": \"${cand.category}\",\n")
                    }
                    append("        \"note\": \"${cand.explanation}\"\n")
                    append("      }")
                }
                append("\n    ]\n")
                append("  }")
            }
            append("\n]")
        }

        val languageRequirement = "所有推荐译名、分类、备注都必须写成简体中文。\n"

        // Few-shot example
        val fakeUser = buildString {
            append("请分析以下候选组并完成合并裁决，$languageRequirement")
            append("---\n")
            append("[\n")
            append("  {\n")
            append("    \"source\": \"亚瑟王\",\n")
            append("    \"merged_sources\": [\"亚瑟王\", \"亚瑟\"],\n")
            append("    \"candidates\": [\n")
            append("      {\"type\": \"character\", \"recommended_translation\": \"亚瑟王\", \"gender\": \"男性\", \"note\": \"历史人物\"},\n")
            append("      {\"type\": \"term\", \"recommended_translation\": \"亚瑟\", \"category_path\": \"称号\", \"note\": \"错误分类为术语\"}\n")
            append("    ]\n")
            append("  },\n")
            append("  {\n")
            append("    \"source\": \"月光斩\",\n")
            append("    \"merged_sources\": [\"月光斩\"],\n")
            append("    \"candidates\": [\n")
            append("      {\"type\": \"term\", \"recommended_translation\": \"月光斩\", \"category_path\": \"技能\", \"note\": \"剑技名称\"}\n")
            append("    ]\n")
            append("  }\n")
            append("]\n")
            append("---\n")
            append("请输出 JSON 合并结果。")
        }

        val fakeAssistant = buildString {
            append("```json\n")
            append("{\n")
            append("  \"characters\": [\n")
            append("    {\"source\": \"亚瑟王\", \"recommended_translation\": \"亚瑟王\", \"gender\": \"男性\", \"note\": \"历史人物\"}\n")
            append("  ],\n")
            append("  \"terms\": [\n")
            append("    {\"source\": \"月光斩\", \"recommended_translation\": \"月光斩\", \"category_path\": \"技能\", \"note\": \"剑技名称\"}\n")
            append("  ]\n")
            append("}\n")
            append("```")
        }

        val userPrompt = buildString {
            append("请分析以下候选组并完成合并裁决，$languageRequirement")
            append("---\n")
            append(batchJson)
            append("\n---\n")
            append("请输出 JSON 合并结果。")
        }

        return listOf(
            LlmRequestConfig.Message("user", fakeUser),
            LlmRequestConfig.Message("assistant", fakeAssistant),
            LlmRequestConfig.Message("user", userPrompt)
        )
    }

    /**
     * Parse Stage-2 LLM response: extract JSON, convert to ExtractedTerm list.
     * Applies source alias remapping (short variant → canonical long form, AiNiee-style).
     * Only returns characters and terms (non_translate is already finalized in Stage 1).
     */
    private fun parseStage2Response(content: String): Result<List<ExtractedTerm>> {
        if (content.isBlank()) return Result.success(emptyList())

        val jsonObj = try {
            val cleaned = extractJsonObject(content)
            if (cleaned.isBlank()) return Result.success(emptyList())
            JSONObject(cleaned)
        } catch (e: Exception) {
            return Result.failure(
                ParseException("Failed to parse Stage-2 JSON: ${e.message}")
            )
        }

        val items = mutableListOf<ExtractedTerm>()

        val charsArray = jsonObj.optJSONArray("characters")
        if (charsArray != null) {
            for (i in 0 until charsArray.length()) {
                val obj = charsArray.optJSONObject(i) ?: continue
                var source = obj.optString("source", "").trim()
                if (source.isBlank()) continue
                // Remap short variant → canonical long form (AiNiee source alias)
                source = stage2SourceAliases[source] ?: source
                items.add(
                    ExtractedTerm(
                        sourceTerm = source,
                        suggestedTarget = obj.optString("recommended_translation", "").trim(),
                        type = ExtractedTerm.TYPE_CHARACTER,
                        category = obj.optString("gender", "").trim(),
                        explanation = obj.optString("note", "").trim()
                    )
                )
            }
        }

        val termsArray = jsonObj.optJSONArray("terms")
        if (termsArray != null) {
            for (i in 0 until termsArray.length()) {
                val obj = termsArray.optJSONObject(i) ?: continue
                var source = obj.optString("source", "").trim()
                if (source.isBlank()) continue
                // Remap short variant → canonical long form (AiNiee source alias)
                source = stage2SourceAliases[source] ?: source
                items.add(
                    ExtractedTerm(
                        sourceTerm = source,
                        suggestedTarget = obj.optString("recommended_translation", "").trim(),
                        type = ExtractedTerm.TYPE_TERM,
                        category = obj.optString("category_path", "").trim(),
                        explanation = obj.optString("note", "").trim()
                    )
                )
            }
        }

        return Result.success(items)
    }

    /**
     * Merge and deduplicate non_translate items from Stage 1.
     * Applies punctuation filtering (AiNiee-style: reject markers that are only punctuation).
     * Independently upgrades category and note from different candidates (AiNiee-style field merging).
     */
    internal fun mergeNonTranslateItems(stage1AllItems: List<ExtractedTerm>): List<ExtractedTerm> {
        val ntItems = stage1AllItems
            .filter { it.type == ExtractedTerm.TYPE_NON_TRANSLATE }
            .filter { !isOnlyPunctuation(it.sourceTerm) } // AiNiee-style punctuation filter

        if (ntItems.isEmpty()) return emptyList()

        // AiNiee-style: independently pick best category and best note from different candidates
        // (don't just replace entire entries — merge fields from best sources)
        val merged = mutableMapOf<String, MutableMap<String, String>>()
        for (item in ntItems) {
            val key = item.sourceTerm.trim()
            if (key.isBlank()) continue
            val fields = merged.getOrPut(key) { mutableMapOf(
                "sourceTerm" to key,
                "type" to ExtractedTerm.TYPE_NON_TRANSLATE,
                "category" to "",
                "explanation" to ""
            ) }
            // AiNiee-style: upgrade category if current is empty/"其他"
            val currentCat = fields["category"] ?: ""
            if (currentCat.isBlank() || currentCat == "其他") {
                if (item.category.isNotBlank() && item.category != "其他") {
                    fields["category"] = item.category
                }
            }
            // AiNiee-style: fill in missing note
            val currentNote = fields["explanation"] ?: ""
            if (currentNote.isBlank() && item.explanation.isNotBlank()) {
                fields["explanation"] = item.explanation
            }
        }

        return merged.values.map { fields ->
            ExtractedTerm(
                sourceTerm = fields["sourceTerm"] ?: "",
                suggestedTarget = "",
                type = ExtractedTerm.TYPE_NON_TRANSLATE,
                category = fields["category"] ?: "",
                explanation = fields["explanation"] ?: ""
            )
        }
    }

    /**
     * AiNiee-style _finalize_results: rescue AI-discarded groups via weighted heuristic.
     *
     * After Stage 2 AI returns, checks which groups the LLM omitted from its output.
     * For unclaimed groups, applies weighted scoring (non-empty/non-default categories
     * get higher weight) to determine character vs term classification.
     *
     * This ensures the LLM can't silently drop valid candidates.
     */
    internal fun finalizeResults(
        stage2Results: List<ExtractedTerm>,
        allStage2Groups: List<Stage2Group>,
        stage1AllItems: List<ExtractedTerm>
    ): List<ExtractedTerm> {
        // Collect sources claimed by Stage 2 output (already remapped via aliases)
        val claimedSources = stage2Results.map { it.sourceTerm.trim().lowercase() }.toSet()

        // Find unclaimed groups — items the LLM omitted or discarded
        val unclaimedGroups = allStage2Groups.filter { group ->
            group.source.trim().lowercase() !in claimedSources
        }

        if (unclaimedGroups.isEmpty()) return stage2Results

        val rescuedItems = mutableListOf<ExtractedTerm>()

        for (group in unclaimedGroups) {
            val candidates = group.candidates
            if (candidates.isEmpty()) continue

            // AiNiee-style weighted scoring:
            // t_score = term_candidates_count + count of terms with non-empty/non-default category
            // c_score = character_candidates_count + count of chars with non-empty/non-default gender
            val tCands = candidates.filter { it.type == ExtractedTerm.TYPE_TERM }
            val cCands = candidates.filter { it.type == ExtractedTerm.TYPE_CHARACTER }

            // Skip if no relevant candidates (pure non_translate finalized in Stage 1)
            if (tCands.isEmpty() && cCands.isEmpty()) continue

            val tScore = tCands.size + tCands.count { it.category.isNotBlank() && it.category != "其他" }
            val cScore = cCands.size + cCands.count { it.category.isNotBlank() && it.category != "其他" }
            val isCharacter = cScore > tScore

            // Pick best translation and category from all candidates
            val bestTranslation = candidates
                .firstOrNull { it.suggestedTarget.isNotBlank() }
                ?.suggestedTarget ?: ""

            val bestCategory = candidates
                .firstOrNull { it.category.isNotBlank() && it.category != "其他" }
                ?.category ?: ""

            val mergedNotes = candidates
                .mapNotNull { it.explanation.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .joinToString(" | ")

            rescuedItems.add(
                ExtractedTerm(
                    sourceTerm = group.source,
                    suggestedTarget = bestTranslation,
                    type = if (isCharacter) ExtractedTerm.TYPE_CHARACTER else ExtractedTerm.TYPE_TERM,
                    category = bestCategory,
                    explanation = mergedNotes
                )
            )
        }

        // Stage 2 results + rescued items (Stage 1 non_translate merged separately)
        return (stage2Results + rescuedItems)
    }

    /**
     * Check if a non_translate marker consists only of punctuation and whitespace characters.
     * Mirrors AiNiee's `COMMON_PUNCTUATION_CHARS` filter in _finalize_results().
     */
    internal fun isOnlyPunctuation(marker: String): Boolean {
        return marker.all { it in PUNCTUATION_CHARS }
    }

    /**
     * Heuristic fallback for Stage 2 when LLM is unavailable.
     * For each group: pick the most common type (character vs term),
     * merge notes, keep the best translation.
     *
     * Pure non_translate groups are skipped — they are finalized in Stage 1
     * via [mergeNonTranslateItems] and should not be reclassified.
     */
    internal fun heuristicDedupBatch(batch: List<Stage2Group>): List<ExtractedTerm> {
        val result = mutableListOf<ExtractedTerm>()
        for (group in batch) {
            val candidates = group.candidates
            if (candidates.isEmpty()) continue

            // Skip pure non_translate groups — they're finalized in Stage 1
            if (candidates.all { it.type == ExtractedTerm.TYPE_NON_TRANSLATE }) continue

            // Only count character and term candidates (ignore non_translate for type voting)
            val charCount = candidates.count { it.type == ExtractedTerm.TYPE_CHARACTER }
            val termCount = candidates.count { it.type == ExtractedTerm.TYPE_TERM }
            val isCharacter = charCount > termCount

            // Pick best translation (non-empty preferred)
            val bestTranslation = candidates
                .firstOrNull { it.suggestedTarget.isNotBlank() }
                ?.suggestedTarget ?: ""

            // Pick best category (empty string if none available)
            val bestCategory = candidates
                .firstOrNull { it.category.isNotBlank() }
                ?.category ?: ""

            // Merge notes
            val mergedNotes = candidates
                .mapNotNull { it.explanation.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .joinToString(" | ")

            result.add(
                ExtractedTerm(
                    sourceTerm = group.source,
                    suggestedTarget = bestTranslation,
                    type = if (isCharacter) ExtractedTerm.TYPE_CHARACTER else ExtractedTerm.TYPE_TERM,
                    category = bestCategory,
                    explanation = mergedNotes
                )
            )
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Extract JSON object from text, handling markdown code fences and extra text.
     */
    internal fun extractJsonObject(content: String): String {
        var cleaned = content.trim()

        // Strip markdown code fences
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val fenceMatch = fenceRegex.find(cleaned)
        if (fenceMatch != null) {
            cleaned = fenceMatch.groupValues[1].trim()
        }

        // Find the JSON object boundaries
        val startIdx = cleaned.indexOf('{')
        val endIdx = cleaned.lastIndexOf('}')

        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            cleaned = cleaned.substring(startIdx, endIdx + 1)
            return cleaned
        }

        return ""
    }

    /**
     * Estimate token count for a Stage 2 group (source + variants + candidates).
     * Used for token-based batch sizing.
     */
    internal fun estimateStage2GroupTokens(group: Stage2Group): Int {
        var tokens = TokenEstimator.estimate(group.source) + 50 // overhead for JSON structure
        for (variant in group.allVariants) {
            tokens += TokenEstimator.estimate(variant) + 10
        }
        for (candidate in group.candidates) {
            tokens += TokenEstimator.estimate(candidate.sourceTerm) + 20
            tokens += TokenEstimator.estimate(candidate.suggestedTarget) + 10
            tokens += TokenEstimator.estimate(candidate.category) + 5
            tokens += TokenEstimator.estimate(candidate.explanation) + 5
        }
        return tokens
    }

    /**
     * Final dedup: group by normalized source term, pick best entry per group.
     * Prefer entries with translations over non_translate entries.
     */
    internal fun deduplicateAndFilter(items: List<ExtractedTerm>): List<ExtractedTerm> {
        if (items.isEmpty()) return emptyList()

        val grouped = mutableMapOf<String, MutableList<ExtractedTerm>>()
        for (item in items) {
            val key = item.sourceTerm.trim().lowercase()
            if (key.isBlank()) continue
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        return grouped.values.map { group ->
            // Prefer: has translation > has category > has explanation > first
            group.maxByOrNull {
                val score = when {
                    it.type == ExtractedTerm.TYPE_NON_TRANSLATE -> 0
                    it.suggestedTarget.isNotBlank() && it.category.isNotBlank() -> 4
                    it.suggestedTarget.isNotBlank() -> 3
                    it.category.isNotBlank() -> 2
                    it.explanation.isNotBlank() -> 1
                    else -> 0
                }
                score
            } ?: group.first()
        }.sortedBy { it.sourceTerm.lowercase() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Model loading & LLM service creation
    // ═══════════════════════════════════════════════════════════════════

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

/**
 * Represents a group of candidate terms that share the same (or similar) source term.
 * Used in Stage 2 for AI dedup/merge.
 *
 * @param source       The canonical/main source term for this group
 * @param allVariants  All variant forms of this term (e.g., "亚瑟王" and "亚瑟")
 * @param candidates   All extraction results from Stage 1 that map to this group
 */
data class Stage2Group(
    val source: String,
    val allVariants: MutableList<String>,
    val candidates: MutableList<ExtractedTerm>
)
