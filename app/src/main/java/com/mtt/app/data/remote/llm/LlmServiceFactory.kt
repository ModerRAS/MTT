package com.mtt.app.data.remote.llm

import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.remote.anthropic.AnthropicClient
import com.mtt.app.data.remote.openai.OpenAiClient

/**
 * Factory for creating provider-specific [LlmService] instances.
 *
 * Dispatches based on [LlmProvider] type:
 * - [LlmProvider.OpenAI] → [OpenAiLlmService]
 * - [LlmProvider.Anthropic] → [AnthropicLlmService]
 */
object LlmServiceFactory {

    /**
     * Create an [LlmService] for the given [provider].
     *
     * @param provider The LLM provider configuration (determines which service to create)
     * @param openAiClient OpenAI HTTP client instance
     * @param anthropicClient Anthropic HTTP client instance
     * @return Provider-specific LlmService implementation
     */
    fun create(
        provider: LlmProvider,
        openAiClient: OpenAiClient,
        anthropicClient: AnthropicClient
    ): LlmService {
        return when (provider) {
            is LlmProvider.OpenAI -> OpenAiLlmService(openAiClient)
            is LlmProvider.Anthropic -> AnthropicLlmService(anthropicClient)
        }
    }
}
