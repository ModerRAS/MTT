package com.mtt.app.data.remote.llm

import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo

/**
 * Registry of all supported LLM models.
 *
 * Provides lookup and filtering capabilities for model selection in the UI.
 */
object ModelRegistry {

    /** GPT-4o — high-capability OpenAI model (128K context) */
    val GPT_4O = ModelInfo(
        modelId = "gpt-4o",
        displayName = "GPT-4o",
        contextWindow = 128000,
        provider = LlmProvider.OpenAI("", "")
    )

    /** GPT-4o Mini — cost-effective OpenAI model (128K context) */
    val GPT_4O_MINI = ModelInfo(
        modelId = "gpt-4o-mini",
        displayName = "GPT-4o Mini",
        contextWindow = 128000,
        provider = LlmProvider.OpenAI("", "")
    )

    /** Claude 3.5 Sonnet — high-capability Anthropic model (200K context) */
    val CLAUDE_SONNET = ModelInfo(
        modelId = "claude-3-5-sonnet-20241022",
        displayName = "Claude 3.5 Sonnet",
        contextWindow = 200000,
        provider = LlmProvider.Anthropic("", "")
    )

    /** Claude 3.5 Haiku — fast Anthropic model (200K context) */
    val CLAUDE_HAIKU = ModelInfo(
        modelId = "claude-3-5-haiku-20241022",
        displayName = "Claude 3.5 Haiku",
        contextWindow = 200000,
        provider = LlmProvider.Anthropic("", "")
    )

    /** All registered models */
    val allModels: List<ModelInfo> = listOf(GPT_4O, GPT_4O_MINI, CLAUDE_SONNET, CLAUDE_HAIKU)

    /** Default OpenAI model: GPT-4o Mini */
    val defaultOpenAiModel: ModelInfo get() = GPT_4O_MINI

    /** Default Anthropic model: Claude 3.5 Haiku */
    val defaultAnthropicModel: ModelInfo get() = CLAUDE_HAIKU

    /**
     * Look up a model by its unique [modelId].
     *
     * @return The matching [ModelInfo] or null if not found
     */
    fun getById(modelId: String): ModelInfo? = allModels.firstOrNull { it.modelId == modelId }

    /**
     * Get all models for a given [provider] type.
     *
     * @return List of [ModelInfo] matching the provider type
     */
    fun getByProvider(provider: LlmProvider): List<ModelInfo> {
        return when (provider) {
            is LlmProvider.OpenAI -> allModels.filter { it.provider is LlmProvider.OpenAI }
            is LlmProvider.Anthropic -> allModels.filter { it.provider is LlmProvider.Anthropic }
        }
    }
}
