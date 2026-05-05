package com.mtt.app.data.model

/**
 * Model information for LLM selection.
 *
 * @param modelId API model identifier (e.g., "gpt-4o")
 * @param displayName Human-readable name for UI display
 * @param contextWindow Maximum token context window
 * @param provider The LLM provider (OpenAI or Anthropic)
 * @param isCustom Whether this model was user-defined (not from presets)
 */
data class ModelInfo(
    val modelId: String,
    val displayName: String,
    val contextWindow: Int,
    val provider: LlmProvider,
    val isCustom: Boolean = false
)
