package com.mtt.app.data.model

import kotlinx.serialization.Serializable

/**
 * Data class representing LLM translation response.
 */
@Serializable
data class TranslationResponse(
    val content: String,
    val model: String,
    val tokensUsed: Int
)