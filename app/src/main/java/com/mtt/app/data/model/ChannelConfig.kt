package com.mtt.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM provider channel type.
 */
@Serializable
enum class ChannelType {
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC
}

/**
 * Model information fetched from an LLM provider API.
 *
 * @param modelId      API model identifier (e.g., "gpt-4o")
 * @param displayName  Human-readable name for UI display
 * @param contextWindow Maximum token context window
 */
@Serializable
data class FetchedModel(
    val modelId: String,
    val displayName: String,
    val contextWindow: Int
)

/**
 * Configuration for a single LLM provider channel.
 *
 * @param id            Unique identifier (UUID string)
 * @param name          User-friendly channel name
 * @param type          OPENAI or ANTHROPIC
 * @param baseUrl       API base URL (e.g., https://api.openai.com/v1)
 * @param apiKey        API key for authentication
 * @param fetchedModels Models fetched from the API
 * @param fetchedAt     Timestamp of last model fetch, null if never fetched
 */
@Serializable
data class ChannelConfig(
    val id: String,
    val name: String,
    val type: ChannelType,
    val baseUrl: String,
    val apiKey: String,
    val fetchedModels: List<FetchedModel> = emptyList(),
    val fetchedAt: Long? = null
)
