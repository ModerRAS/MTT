package com.mtt.app.data.model

/**
 * Model information for LLM selection.
 */
data class ModelInfo(
    val modelId: String,
    val displayName: String,
    val contextWindow: Int,
    val provider: LlmProvider
)
