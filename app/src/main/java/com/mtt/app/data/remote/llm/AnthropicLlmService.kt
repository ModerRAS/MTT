package com.mtt.app.data.remote.llm

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.remote.anthropic.AnthropicClient

/**
 * Anthropic implementation of [LlmService].
 *
 * Thin wrapper that delegates to [AnthropicClient], mapping [LlmRequestConfig]
 * fields to the client's translate() method.
 */
class AnthropicLlmService(
    private val anthropicClient: AnthropicClient
) : LlmService {

    @Throws(ApiException::class, NetworkException::class)
    override suspend fun translate(config: LlmRequestConfig): TranslationResponse {
        return anthropicClient.translate(
            messages = config.messages,
            systemPrompt = config.systemPrompt,
            model = config.model.modelId,
            maxTokens = config.maxTokens
        )
    }

    @Throws(ApiException::class, NetworkException::class)
    override suspend fun testConnection(modelId: String): Boolean {
        return anthropicClient.testConnectionDirect(modelId)
    }
}
