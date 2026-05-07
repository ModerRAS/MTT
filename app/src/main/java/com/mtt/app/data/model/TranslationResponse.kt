package com.mtt.app.data.model

import kotlinx.serialization.Serializable

/**
 * Data class representing LLM translation response.
 *
 * @param content           Raw text response (or tool call arguments JSON for tool mode).
 * @param model             Model identifier used for the response.
 * @param tokensUsed        Total tokens consumed (input + output).
 * @param translations      Flat list of translated strings (simple array tool mode).
 * @param translationPairs  Source→translated pairs extracted from tool call (object array mode).
 */
@Serializable
data class TranslationResponse(
    val content: String,
    val model: String,
    val tokensUsed: Int,
    val translations: List<String>? = null,
    val translationPairs: List<TranslationPair>? = null
)

/**
 * A source→translated pair returned by the LLM via tool calling.
 */
@Serializable
data class TranslationPair(
    val source: String,
    val translated: String
)