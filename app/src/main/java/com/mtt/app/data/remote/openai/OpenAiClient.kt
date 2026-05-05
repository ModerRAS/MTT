package com.mtt.app.data.remote.openai

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationResponse
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration

/**
 * OpenAI API client for translation using the official openai-java SDK.
 *
 * Uses the Chat Completions API with system message as first message
 * (following the pattern from AiNiee OpenaiRequester.py).
 *
 * @param okHttpClient Base OkHttpClient from HttpClientFactory (auth headers included)
 * @param apiKey OpenAI API key
 * @param baseUrl Base URL for the OpenAI API (default: https://api.openai.com/v1)
 */
class OpenAiClient(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) {
    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .maxRetries(0) // Retry is handled by HttpClientFactory's RetryInterceptor
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build()
    }

    /**
     * Translate text using the OpenAI Chat Completions API (non-streaming).
     *
     * Messages are built with system message FIRST, followed by user messages —
     * matching the pattern from AiNiee OpenaiRequester.py lines 188-195.
     *
     * @param messages User messages to translate
     * @param systemPrompt System prompt for translation instructions
     * @param model OpenAI model name (e.g., "gpt-4o-mini")
     * @return TranslationResponse with translated content
     * @throws ApiException on API errors (401, 429, 5xx)
     * @throws NetworkException on connectivity errors
     */
    @Throws(ApiException::class, NetworkException::class)
    fun translate(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String
    ): TranslationResponse {
        try {
            val params = buildCreateParams(messages, systemPrompt, model)
            val completion = client.chat().completions().create(params)

            val content = StringBuilder()
            for (choice in completion.choices()) {
                choice.message().content().ifPresent { content.append(it) }
            }

            val tokensUsed = completion.usage()
                .map { usage -> usage.promptTokens() + usage.completionTokens() }
                .orElse(0)
                .toInt()

            return TranslationResponse(
                content = content.toString(),
                model = model,
                tokensUsed = tokensUsed
            )
        } catch (e: IOException) {
            AppLogger.e(TAG, "OpenAI translate IOException: ${e.message}", e)
            throw NetworkException("网络连接失败，请检查网络: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "OpenAI translate failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw mapApiError(e)
        }
    }

    private fun buildCreateParams(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(model)

        // FIRST: system message (matches AiNiee pattern — messages.insert(0, system_msg))
        builder.addSystemMessage(systemPrompt)

        // THEN: user messages from conversation history
        for (message in messages) {
            builder.addUserMessage(message.content)
        }

        return builder.build()
    }

    private fun mapApiError(e: Exception): Exception {
        val message = e.message ?: ""
        val errorType = e.javaClass.simpleName
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
                ApiException(0, "API 请求失败 [$errorType]: $message")
        }
    }
    
    /**
     * Test connection by making a direct HTTP call using our OkHttpClient.
     * Bypasses the OpenAI Java SDK to ensure our interceptors and timeouts are used.
     *
     * @param model Model ID to test with
     * @return true if the API responded successfully
     * @throws Exception with details on failure
     */
    fun testConnectionDirect(model: String): Boolean {
        val jsonBody = """{"model":"$model","messages":[{"role":"user","content":"hi"}],"max_tokens":5}"""
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw ApiException(response.code, "HTTP ${response.code}: $body")
        }
        return true
    }

    companion object {
        private const val TAG = "OpenAiClient"
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }
}
