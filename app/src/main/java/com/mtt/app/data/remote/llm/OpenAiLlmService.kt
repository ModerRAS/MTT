package com.mtt.app.data.remote.llm

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.remote.openai.OpenAiClient

/**
 * OpenAI implementation of [LlmService].
 *
 * Thin wrapper that delegates to [OpenAiClient], mapping [LlmRequestConfig]
 * fields to the client's translate() method.
 */
class OpenAiLlmService(
    private val openAiClient: OpenAiClient
) : LlmService {

    @Throws(ApiException::class, NetworkException::class)
    override suspend fun translate(config: LlmRequestConfig): TranslationResponse {
        return openAiClient.translate(
            messages = config.messages,
            systemPrompt = config.systemPrompt,
            model = config.model.modelId
        )
    }

    @Throws(ApiException::class, NetworkException::class)
    override suspend fun testConnection(modelId: String): Boolean {
        return openAiClient.testConnectionDirect(modelId)
    }
}
