package com.mtt.app.data.model

/**
 * Data class for LLM request configuration.
 */
data class LlmRequestConfig(
    val messages: List<Message>,
    val systemPrompt: String,
    val model: ModelInfo,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 16384,
    /** If non-null, forces the model to call this tool (function calling) instead of generating free-text. */
    val toolChoice: String? = null
) {
    data class Message(
        val role: String,
        val content: String
    )
}