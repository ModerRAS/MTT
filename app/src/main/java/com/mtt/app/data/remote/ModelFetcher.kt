package com.mtt.app.data.remote

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.MttException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.core.error.Result
import com.mtt.app.data.model.ChannelConfig
import com.mtt.app.data.model.ChannelType
import com.mtt.app.data.model.FetchedModel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * Utility class for fetching available models from LLM provider APIs.
 *
 * - OpenAI-compatible APIs: GET {baseUrl}/v1/models with Bearer auth
 * - Anthropic: Returns hardcoded list (no public models list API)
 *
 * @param client OkHttpClient for HTTP requests (injected via Hilt)
 */
class ModelFetcher @Inject constructor(
    private val client: OkHttpClient
) {

    /**
     * Fetch available models for the given channel.
     *
     * @param channel Channel configuration containing type, baseUrl, and apiKey
     * @return Result with list of FetchedModel, or failure with exception
     */
    fun fetchModels(channel: ChannelConfig): Result<List<FetchedModel>> {
        return when (channel.type) {
            ChannelType.OPENAI -> fetchOpenAiModels(channel)
            ChannelType.ANTHROPIC -> fetchAnthropicModels()
        }
    }

    /**
     * Fetch models from OpenAI-compatible API.
     *
     * Sends GET to {baseUrl}/v1/models (appends /v1 if not present).
     * Parses JSON response looking for data[i].id field.
     * Returns empty list if response doesn't match expected format.
     */
    private fun fetchOpenAiModels(channel: ChannelConfig): Result<List<FetchedModel>> {
        return try {
            val baseUrl = channel.baseUrl.trimEnd('/')
            val url = if (baseUrl.endsWith("/v1")) "$baseUrl/models" else "$baseUrl/v1/models"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${channel.apiKey}")
                .header("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return Result.failure(
                NetworkException("服务器返回空响应")
            )

            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(response.code, "获取模型列表失败: $responseBody")
                )
            }

            val json = JSONObject(responseBody)
            val dataArray = json.optJSONArray("data")

            if (dataArray == null) {
                // Some APIs don't follow OpenAI format, return empty list
                return Result.success(emptyList())
            }

            val models = mutableListOf<FetchedModel>()
            for (i in 0 until dataArray.length()) {
                val modelJson = dataArray.getJSONObject(i)
                val modelId = modelJson.optString("id", "")
                if (modelId.isNotBlank()) {
                    models.add(
                        FetchedModel(
                            modelId = modelId,
                            displayName = modelId,  // Use model ID as display name by default
                            contextWindow = 128000   // Conservative default
                        )
                    )
                }
            }

            Result.success(models)
        } catch (e: Exception) {
            when (e) {
                is MttException -> Result.failure(e)
                else -> Result.failure(NetworkException("网络请求失败: ${e.message}"))
            }
        }
    }

    /**
     * Return hardcoded list of Anthropic Claude models.
     *
     * Anthropic doesn't provide a public models list API,
     * so we maintain a static list of known models.
     */
    private fun fetchAnthropicModels(): Result<List<FetchedModel>> {
        return Result.success(
            listOf(
                FetchedModel("claude-sonnet-4-20250514", "Claude Sonnet 4", 200000),
                FetchedModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200000),
                FetchedModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200000),
                FetchedModel("claude-3-opus-20240229", "Claude 3 Opus", 200000),
                FetchedModel("claude-3-sonnet-20240229", "Claude 3 Sonnet", 200000),
                FetchedModel("claude-3-haiku-20240307", "Claude 3 Haiku", 200000)
            )
        )
    }
}