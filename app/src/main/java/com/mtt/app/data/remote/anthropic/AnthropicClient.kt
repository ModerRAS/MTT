package com.mtt.app.data.remote.anthropic

import com.anthropic.client.AnthropicClient as AnthropicSdkClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationResponse
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Duration

/**
 * Anthropic API client for translation using the official anthropic-java SDK.
 *
 * Uses the Messages API with separate system prompt parameter
 * (Anthropic does NOT use role="system" messages like OpenAI).
 *
 * @param okHttpClient Base OkHttpClient from HttpClientFactory
 * @param apiKey Anthropic API key
 * @param baseUrl Base URL for the Anthropic API (default: https://api.anthropic.com)
 */
class AnthropicClient(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com"
) {
    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 4096L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }

    private val client: AnthropicSdkClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .maxRetries(0) // Retry is handled by HttpClientFactory's RetryInterceptor
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .putHeader("anthropic-version", ANTHROPIC_VERSION)
            .build()
    }

    /**
     * Translate text using the Anthropic Messages API.
     *
     * @param messages User messages to translate (system role messages MUST NOT be included)
     * @param systemPrompt System prompt sent as a separate parameter
     * @param model Anthropic model name (e.g., "claude-3-5-sonnet-20241022")
     * @param maxTokens Maximum tokens in response (default: 4096)
     * @return TranslationResponse with translated content
     * @throws ApiException on API errors (401, 429, 5xx)
     * @throws NetworkException on connectivity errors
     */
    @Throws(ApiException::class, NetworkException::class)
    fun translate(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS.toInt()
    ): TranslationResponse {
        try {
            val params = buildMessageParams(messages, systemPrompt, model, maxTokens.toLong())
            val response = client.messages().create(params)

            val content = extractContent(response)
            val tokensUsed = (response.usage().inputTokens() + response.usage().outputTokens()).toInt()

            return TranslationResponse(
                content = content,
                model = model,
                tokensUsed = tokensUsed
            )
        } catch (e: IOException) {
            throw NetworkException("网络连接失败，请检查网络")
        } catch (e: Exception) {
            throw mapApiError(e)
        }
    }

    private fun buildMessageParams(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String,
        maxTokens: Long
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(systemPrompt)

        for (message in messages) {
            builder.addUserMessage(message.content)
        }

        return builder.build()
    }

    private fun extractContent(response: com.anthropic.models.messages.Message): String {
        val sb = StringBuilder()
        for (block in response.content()) {
            block.text().ifPresent { textBlock ->
                sb.append(textBlock.text())
            }
        }
        return sb.toString()
    }

    private fun mapApiError(e: Exception): Exception {
        val message = e.message ?: ""
        return when {
            message.contains("401") || message.contains("unauthorized", ignoreCase = true) ->
                ApiException.authFailure()
            message.contains("429") || message.contains("rate", ignoreCase = true) ->
                ApiException.rateLimit()
            message.contains("500") || message.contains("503") ||
                    message.contains("502") || message.contains("504") ->
                ApiException.serverError()
            message.contains("403") ->
                ApiException.forbidden()
            else ->
                ApiException(0, "API 请求失败: $message")
        }
    }
}
