package com.mtt.app.data.remote.llm

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.remote.openai.OpenAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        return withContext(Dispatchers.IO) {
            openAiClient.translate(
                messages = config.messages,
                systemPrompt = config.systemPrompt,
                model = config.model.modelId,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                toolChoice = config.toolChoice,
                toolDefinitionJson = config.toolDefinitionJson
            )
        }
    }

    @Throws(ApiException::class, NetworkException::class)
    override suspend fun testConnection(modelId: String): Boolean {
        return withContext(Dispatchers.IO) {
            openAiClient.testConnectionDirect(modelId)
        }
    }
}
