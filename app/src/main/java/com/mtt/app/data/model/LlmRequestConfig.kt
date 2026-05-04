package com.mtt.app.data.model

/**
 * Data class for LLM request configuration.
 */
data class LlmRequestConfig(
    val messages: List<Message>,
    val systemPrompt: String,
    val model: ModelInfo,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
) {
    data class Message(
        val role: String,
        val content: String
    )
}