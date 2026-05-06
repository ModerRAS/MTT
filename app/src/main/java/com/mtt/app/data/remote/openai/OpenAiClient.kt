package com.mtt.app.data.remote.openai

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    /**
     * Translate text using the OpenAI-compatible Chat Completions API (non-streaming).
     *
     * Uses our OkHttpClient directly so that LoggingInterceptor, RetryInterceptor,
     * and custom timeouts are applied. Bypasses the OpenAI Java SDK which creates
     * its own internal HTTP client ignoring our configuration.
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
        model: String,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): TranslationResponse {
        try {
            val jsonBody = buildJsonBody(messages, systemPrompt, model, temperature, maxTokens)
            val normalizedBaseUrl = normalizeApiUrl(baseUrl)
            val url = "$normalizedBaseUrl/chat/completions"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw ApiException(response.code, body.ifEmpty { "HTTP ${response.code}" })
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            val content = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content", "") ?: ""
            } else ""

            val usage = json.optJSONObject("usage")
            val tokensUsed = if (usage != null) {
                usage.optInt("prompt_tokens", 0) + usage.optInt("completion_tokens", 0)
            } else 0

            return TranslationResponse(
                content = content,
                model = model,
                tokensUsed = tokensUsed
            )
        } catch (e: IOException) {
            AppLogger.e(TAG, "OpenAI translate IOException: ${e.message}", e)
            throw NetworkException("网络连接失败，请检查网络: ${e.message}")
        } catch (e: ApiException) {
            // ApiException from non-successful HTTP response — re-map using code + body
            AppLogger.e(TAG, "OpenAI translate HTTP error: ${e.code}: ${e.message}", e)
            throw mapApiError(e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "OpenAI translate failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw mapApiError(e)
        }
    }

    /**
     * Build a JSON request body for the chat completions API.
     * Format: {"model":"...","messages":[{"role":"system","content":"..."},...]}
     */
    private fun buildJsonBody(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): String {
        val root = JSONObject()
        root.put("model", model)

        // Optional parameters for deterministic output and cost control
        if (temperature != null) {
            root.put("temperature", temperature.toDouble())
        }
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens)
        }

        val msgs = JSONArray()
        // System message first (matches AiNiee pattern)
        val sysMsg = JSONObject()
        sysMsg.put("role", "system")
        sysMsg.put("content", systemPrompt)
        msgs.put(sysMsg)

        // User messages
        for (msg in messages) {
            val userMsg = JSONObject()
            userMsg.put("role", msg.role)
            userMsg.put("content", msg.content)
            msgs.put(userMsg)
        }

        root.put("messages", msgs)
        return root.toString()
    }

    private fun mapApiError(e: Exception): Exception {
        // If it's already an ApiException with a real status code, preserve the code
        if (e is ApiException && e.code > 0) {
            val msg = e.message ?: ""
            return when {
                e.code == 401 || msg.contains("unauthorized", ignoreCase = true) ->
                    ApiException.authFailure()
                e.code == 429 || msg.contains("rate", ignoreCase = true) ->
                    ApiException.rateLimit()
                e.code in 500..599 ->
                    ApiException.serverError()
                e.code == 403 ->
                    ApiException.forbidden()
                else ->
                    ApiException(e.code, "API 请求失败: $msg")
            }
        }
        // Generic exceptions — match by message patterns
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
        val normalizedBaseUrl = normalizeApiUrl(baseUrl)
        val url = "$normalizedBaseUrl/chat/completions"
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

    /**
     * Normalize the API base URL to ensure it includes the /v1 path segment
     * required by OpenAI-compatible chat completion endpoints.
     *
     * Standard format: {base_url}/v1/chat/completions
     * If the base URL doesn't include a version path (e.g., /v1, /v2),
     * appends /v1 automatically.
     *
     * Examples:
     *   "https://api.openai.com/v1"       → "https://api.openai.com/v1"
     *   "https://api.deepseek.com"        → "https://api.deepseek.com/v1"
     *   "https://api.deepseek.com/v1"     → "https://api.deepseek.com/v1"
     *   "https://custom.example.com/v2"   → "https://custom.example.com/v2" (unchanged)
     */
    private fun normalizeApiUrl(baseUrl: String): String {
        val url = baseUrl.trimEnd('/')
        // Check if the URL already contains a version path segment (/v1, /v2, etc.)
        val versionPathRegex = Regex("/v\\d+$")
        return if (versionPathRegex.containsMatchIn(url)) url else "$url/v1"
    }

    companion object {
        private const val TAG = "OpenAiClient"
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }
}
