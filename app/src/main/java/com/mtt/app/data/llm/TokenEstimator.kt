package com.mtt.app.data.llm

import com.mtt.app.data.model.ModelInfo

/**
 * Token estimator using character-based approximation.
 * Falls back when tiktoken is not available on Android.
 */
object TokenEstimator {

    private val CJK_REGEX = Regex("[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af]")

    // Token estimation ratios
    private const val ASCII_CHARS_PER_TOKEN = 4.0
    private const val CJK_CHARS_PER_TOKEN = 2.0
    private const val PROMPT_OVERHEAD_TOKENS = 100

    /**
     * Estimate token count for a single text using character-based approximation.
     *
     * @param text The input text to estimate tokens for
     * @return Estimated token count
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        val cjkMatches = CJK_REGEX.findAll(text).map { it.value }
        val cjkChars = cjkMatches.joinToString("").length
        val asciiChars = text.length - cjkChars

        // Calculate weighted average based on actual character composition
        val cjkTokens = cjkChars / CJK_CHARS_PER_TOKEN
        val asciiTokens = asciiChars / ASCII_CHARS_PER_TOKEN

        return kotlin.math.ceil(cjkTokens + asciiTokens).toInt()
    }

    /**
     * Estimate total tokens for a batch of texts with prompt overhead.
     *
     * @param texts List of text items to translate
     * @param systemPrompt System prompt content
     * @param userPromptPrefix Prefix for user prompts (e.g., "Translate: ")
     * @return Total estimated token count
     */
    fun estimateBatch(
        texts: List<String>,
        systemPrompt: String,
        userPromptPrefix: String
    ): Int {
        val textTokens = texts.sumOf { estimate(it) }
        val systemTokens = estimate(systemPrompt)
        val promptPrefixTokens = texts.size * estimate(userPromptPrefix)

        return textTokens + systemTokens + promptPrefixTokens + PROMPT_OVERHEAD_TOKENS
    }

    /**
     * Check if tokens can fit within model's context window (using 90% buffer).
     *
     * @param totalTokens Total tokens to check
     * @param model Model information containing context window
     * @return true if tokens fit within safe limit
     */
    fun canFitInContext(totalTokens: Int, model: ModelInfo): Boolean {
        val maxTokens = (model.contextWindow * 0.9).toInt()
        return totalTokens < maxTokens
    }
}