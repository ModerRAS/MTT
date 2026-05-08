package com.mtt.app.data.remote.openai

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationPair
import com.mtt.app.data.model.TranslationResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * OpenAI API client for translation using the official openai-java SDK.
 *
 * Uses streaming Chat Completions API (stream=true) to avoid read timeouts
 * on slow servers — tokens arrive incrementally as they are generated.
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
     * Translate text using streaming Chat Completions API.
     *
     * Uses SSE (Server-Sent Events) to receive tokens incrementally.
     * This avoids read timeouts on slow servers since data keeps flowing
     * as the model generates each token.
     *
     * @param messages User messages to translate
     * @param systemPrompt System prompt for translation instructions
     * @param model OpenAI model name (e.g., "gpt-4o-mini")
     * @return TranslationResponse with translated content
     * @throws ApiException on API errors (401, 429, 5xx)
     * @throws NetworkException on connectivity errors
     */
    /**
     * Translate text using streaming Chat Completions API.
     *
     * When [toolChoice] is non-null, adds a tool definition (`type: "function"`) and
     * forces the model to call `output_translations(translations: string[])` instead
     * of generating free-text. This avoids `<textarea>` parsing issues with reasoning
     * models — the translations arrive as structured JSON in the tool call arguments.
     *
     * @param toolChoice Function name to force as tool_choice, or null for normal text mode.
     */
    @Throws(ApiException::class, NetworkException::class)
    fun translate(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String,
        temperature: Float? = null,
        maxTokens: Int? = null,
        toolChoice: String? = null,
        toolDefinitionJson: String? = null
    ): TranslationResponse {
        try {
            val jsonBody = buildJsonBody(
                messages, systemPrompt, model, temperature, maxTokens,
                stream = true, toolChoice = toolChoice,
                toolDefinitionJson = toolDefinitionJson
            )
            val normalizedBaseUrl = normalizeApiUrl(baseUrl)
            val url = "$normalizedBaseUrl/chat/completions"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw ApiException(response.code, errBody.ifEmpty { "HTTP ${response.code}" })
            }

            // Parse SSE stream
            return parseSseStream(response, model, toolChoice != null)
        } catch (e: IOException) {
            AppLogger.e(TAG, "OpenAI translate IOException: ${e.message}", e)
            throw NetworkException("网络连接失败，请检查网络: ${e.message}")
        } catch (e: ApiException) {
            AppLogger.e(TAG, "OpenAI translate HTTP error: ${e.code}: ${e.message}", e)
            throw mapApiError(e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "OpenAI translate failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw mapApiError(e)
        }
    }

    /**
     * Parse an SSE (Server-Sent Events) stream from the Chat Completions API.
     *
     * Supports two modes:
     * 1. **Text mode** ([isToolCall]=false): accumulates `delta.content` — the normal response text.
     * 2. **Tool call mode** ([isToolCall]=true): accumulates `delta.tool_calls[].function.arguments`
     *    JSON, then parses it to extract the translations array.
     *
     * SSE format (text mode):
     *   data: {"choices":[{"delta":{"content":"..."}}]}
     *   data: {"choices":[{"delta":{"content":"..."}}]}
     *   data: [DONE]
     *
     * SSE format (tool call mode):
     *   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"..."}}]}}]}
     *   data: [DONE]
     *
     * @param response   OkHttp Response with a streaming body
     * @param model      Model name for the TranslationResponse
     * @param isToolCall If true, parse tool call arguments instead of content
     * @return TranslationResponse with content or structured translations
     */
    private fun parseSseStream(response: Response, model: String, isToolCall: Boolean = false): TranslationResponse {
        val body = response.body ?: throw NetworkException("响应体为空")
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
        val contentBuilder = StringBuilder()
        val toolCallArgsBuilder = StringBuilder()
        var tokensUsed = 0
        var inputTokens = 0
        var outputTokens = 0

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue

                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            if (isToolCall) {
                                // Accumulate tool call arguments
                                val toolCalls = delta.optJSONArray("tool_calls")
                                if (toolCalls != null) {
                                    for (i in 0 until toolCalls.length()) {
                                        val tc = toolCalls.getJSONObject(i)
                                        val func = tc.optJSONObject("function")
                                        if (func != null) {
                                            toolCallArgsBuilder.append(func.optString("arguments", ""))
                                        }
                                    }
                                }
                            } else {
                                val content = delta.optString("content", "")
                                contentBuilder.append(content)
                            }
                        }
                    }

                    // Token usage may appear in the final chunk
                    val usage = json.optJSONObject("usage")
                    if (usage != null) {
                        tokensUsed = usage.optInt("total_tokens", 0)
                        inputTokens = usage.optInt("prompt_tokens", 0)
                        outputTokens = usage.optInt("completion_tokens", 0)
                    }
                } catch (_: Exception) {
                    // Skip malformed JSON lines
                }
            }
        } finally {
            reader.close()
            body.close()
        }

        // If tool call mode, parse accumulated arguments JSON
        if (isToolCall && toolCallArgsBuilder.isNotEmpty()) {
            val argsJson = toolCallArgsBuilder.toString()
            AppLogger.d(TAG, "SSE tool call args: ${argsJson.take(200)} (${argsJson.length} chars)")
            try {
                val argsObj = JSONObject(argsJson)
                val translationsArray = argsObj.optJSONArray("translations")
                if (translationsArray != null) {
                    // Try object array mode: [{source, translated}, ...]
                    val pairs = mutableListOf<TranslationPair>()
                    val flatTranslations = mutableListOf<String>()
                    var isObjectMode = false

                    for (i in 0 until translationsArray.length()) {
                        val item = translationsArray.get(i)
                        if (item is JSONObject) {
                            isObjectMode = true
                            val source = item.optString("source", "")
                            val translated = item.optString("translated", "")
                            pairs.add(TranslationPair(source = source, translated = translated))
                            flatTranslations.add(translated)
                        } else if (item is String) {
                            flatTranslations.add(item)
                        }
                    }

                    if (isObjectMode) {
                        AppLogger.d(TAG, "SSE tool call complete: ${pairs.size} pairs, $tokensUsed tokens (input=$inputTokens, output=$outputTokens)")
                        return TranslationResponse(
                            content = argsJson,
                            model = model,
                            tokensUsed = tokensUsed,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            translations = flatTranslations,
                            translationPairs = pairs
                        )
                    } else {
                        AppLogger.d(TAG, "SSE tool call complete: ${flatTranslations.size} items, $tokensUsed tokens (input=$inputTokens, output=$outputTokens)")
                        return TranslationResponse(
                            content = argsJson,
                            model = model,
                            tokensUsed = tokensUsed,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            translations = flatTranslations
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse tool call args JSON: ${e.message}")
            }
            // Fall through to return as content if JSON parsing fails
        }

        AppLogger.d(TAG, "SSE stream complete: ${contentBuilder.length} chars, $tokensUsed tokens (input=$inputTokens, output=$outputTokens)")

        return TranslationResponse(
            content = contentBuilder.toString(),
            model = model,
            tokensUsed = tokensUsed,
            inputTokens = inputTokens,
            outputTokens = outputTokens
        )
    }

    /**
     * Build a JSON request body for the chat completions API.
     *
     * When [toolChoice] is non-null, adds a `tools` array with a single
     * `output_translations` function tool and sets `tool_choice` to force
     * the model to use it. The tool accepts a JSON array of translated strings.
     *
     * When [stream] is true, the API returns tokens incrementally via SSE.
     */
    private fun buildJsonBody(
        messages: List<LlmRequestConfig.Message>,
        systemPrompt: String,
        model: String,
        temperature: Float? = null,
        maxTokens: Int? = null,
        stream: Boolean = false,
        toolChoice: String? = null,
        toolDefinitionJson: String? = null
    ): String {
        val root = JSONObject()
        root.put("model", model)

        if (temperature != null) {
            root.put("temperature", temperature.toDouble())
        }
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens)
        }
        root.put("stream", stream)

        val msgs = JSONArray()
        val sysMsg = JSONObject()
        sysMsg.put("role", "system")
        sysMsg.put("content", systemPrompt)
        msgs.put(sysMsg)

        for (msg in messages) {
            val userMsg = JSONObject()
            userMsg.put("role", msg.role)
            userMsg.put("content", msg.content)
            msgs.put(userMsg)
        }

        root.put("messages", msgs)

        // Tool calling mode
        if (toolChoice != null) {
            if (toolDefinitionJson != null) {
                // Use custom tool definition (e.g., for term extraction)
                val tools = JSONArray()
                val parsedTool = JSONObject(toolDefinitionJson)
                parsedTool.put("type", "function")
                tools.put(parsedTool)
                root.put("tools", tools)
            } else {
                // Default: output_translations tool for translation pipeline
                val tools = JSONArray()
                val toolObj = JSONObject()
                toolObj.put("type", "function")

                val func = JSONObject()
                func.put("name", "output_translations")
                func.put("description", "Output the translated texts in the same order as the input items")

                val params = JSONObject()
                params.put("type", "object")

                val props = JSONObject()
                val translations = JSONObject()
                translations.put("type", "array")
                translations.put("description", "Translated results, each item pairs source text with its translation")

                val itemSchema = JSONObject()
                itemSchema.put("type", "object")
                val itemProps = JSONObject()

                val sourceProp = JSONObject()
                sourceProp.put("type", "string")
                sourceProp.put("description", "Original source text (without numbering prefix, just the raw text)")
                itemProps.put("source", sourceProp)

                val translatedProp = JSONObject()
                translatedProp.put("type", "string")
                translatedProp.put("description", "Translated text in the target language")
                itemProps.put("translated", translatedProp)

                itemSchema.put("properties", itemProps)
                itemSchema.put("required", JSONArray(listOf("source", "translated")))
                translations.put("items", itemSchema)

                props.put("translations", translations)
                params.put("properties", props)
                params.put("required", JSONArray(listOf("translations")))
                func.put("parameters", params)

                toolObj.put("function", func)
                tools.put(toolObj)
                root.put("tools", tools)
            }

            val choice = JSONObject()
            choice.put("type", "function")
            choice.put("function", JSONObject().apply { put("name", toolChoice) })
            root.put("tool_choice", choice)
        }

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
