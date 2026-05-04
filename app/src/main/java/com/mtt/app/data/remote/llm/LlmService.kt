package com.mtt.app.data.remote.llm

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationResponse

/**
 * Unified LLM service interface for translation.
 *
 * Implementations dispatch to provider-specific clients (OpenAI, Anthropic).
 * All methods should be called from a background thread / coroutine.
 *
 * @ThreadSafe - implementations are stateless beyond the underlying HTTP client
 */
interface LlmService {

    /**
     * Translate text using the configured LLM.
     *
     * @param config Full request configuration including messages, system prompt and model selection
     * @return TranslationResponse with translated content and token usage
     * @throws ApiException on API errors (401, 429, 5xx)
     * @throws NetworkException on connectivity errors
     */
    @Throws(ApiException::class, NetworkException::class)
    suspend fun translate(config: LlmRequestConfig): TranslationResponse

    /**
     * Test connectivity by sending a minimal API call to verify the API key works.
     *
     * @return true if the API key is valid and the service is reachable
     */
    @Throws(ApiException::class, NetworkException::class)
    suspend fun testConnection(): Boolean
}
