package com.mtt.app.data.remote.llm

import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo

/**
 * Registry of all supported LLM models.
 *
 * Provides lookup and filtering capabilities for model selection in the UI.
 * Also supports custom (user-defined) models that are not in the preset list.
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

    /** DeepSeek V4 Flash — latest DeepSeek Flash model */
    val DEEPSEEK_V4_FLASH = ModelInfo(
        modelId = "deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        contextWindow = 128000,
        provider = LlmProvider.OpenAI("", "")
    )

    /** DeepSeek Chat — DeepSeek's chat model */
    val DEEPSEEK_CHAT = ModelInfo(
        modelId = "deepseek-chat",
        displayName = "DeepSeek Chat",
        contextWindow = 64000,
        provider = LlmProvider.OpenAI("", "")
    )

    /** DeepSeek Reasoner — DeepSeek's reasoning model (64K context) */
    val DEEPSEEK_REASONER = ModelInfo(
        modelId = "deepseek-reasoner",
        displayName = "DeepSeek Reasoner",
        contextWindow = 64000,
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

    /** All preset (built-in) models */
    val presetModels: List<ModelInfo> = listOf(GPT_4O, GPT_4O_MINI, DEEPSEEK_V4_FLASH, DEEPSEEK_CHAT, DEEPSEEK_REASONER, CLAUDE_SONNET, CLAUDE_HAIKU)

    /** User-defined custom models (loaded from SecureStorage at runtime). */
    val customModels: MutableList<ModelInfo> = mutableListOf()

    /** Combined list: preset + custom models */
    val allModels: List<ModelInfo> get() = presetModels + customModels

    /** Default OpenAI model: DeepSeek V4 Flash */
    val defaultOpenAiModel: ModelInfo get() = DEEPSEEK_V4_FLASH

    /** Default Anthropic model: Claude 3.5 Haiku */
    val defaultAnthropicModel: ModelInfo get() = CLAUDE_HAIKU

    /**
     * Look up a model by its unique [modelId].
     * Searches both preset and custom models.
     *
     * @return The matching [ModelInfo] or null if not found
     */
    fun getById(modelId: String): ModelInfo? = allModels.firstOrNull { it.modelId == modelId }

    /**
     * Get all models for a given [provider] type (preset + custom).
     *
     * @return List of [ModelInfo] matching the provider type
     */
    fun getByProvider(provider: LlmProvider): List<ModelInfo> {
        return when (provider) {
            is LlmProvider.OpenAI -> allModels.filter { it.provider is LlmProvider.OpenAI }
            is LlmProvider.Anthropic -> allModels.filter { it.provider is LlmProvider.Anthropic }
        }
    }

    /**
     * Create a custom ModelInfo with user-specified parameters.
     * These models are not in the preset list and are managed by the user.
     *
     * @param modelId The API model identifier (e.g., "gpt-4-turbo")
     * @param displayName Optional display name (falls back to modelId)
     * @param contextWindow Token context window for token estimation
     * @param provider The LLM provider (OpenAI or Anthropic)
     */
    fun createCustom(
        modelId: String,
        displayName: String? = null,
        contextWindow: Int = 128000,
        provider: LlmProvider = LlmProvider.OpenAI("", "")
    ): ModelInfo {
        return ModelInfo(
            modelId = modelId,
            displayName = displayName ?: modelId,
            contextWindow = contextWindow,
            provider = provider,
            isCustom = true
        )
    }

    /**
     * Add a custom model to the registry.
     * Replaces an existing custom model with the same modelId.
     */
    fun addCustomModel(model: ModelInfo) {
        customModels.removeAll { it.modelId == model.modelId }
        customModels.add(model)
    }

    /**
     * Remove a custom model by its modelId.
     * @return true if a model was removed
     */
    fun removeCustomModel(modelId: String): Boolean {
        return customModels.removeAll { it.modelId == modelId }
    }

    /**
     * Initialize custom models from a list (loaded from persistence).
     */
    fun initCustomModels(models: List<ModelInfo>) {
        customModels.clear()
        customModels.addAll(models)
    }
}
