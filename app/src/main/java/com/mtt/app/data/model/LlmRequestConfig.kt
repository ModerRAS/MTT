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
    val toolChoice: String? = null,
    /**
     * Custom tool definition JSON. When set alongside [toolChoice], this JSON is
     * injected directly into the tools array instead of the default translation tool.
     * Expected format: a full tool object (name, description, parameters schema).
     */
    val toolDefinitionJson: String? = null
) {
    data class Message(
        val role: String,
        val content: String
    )
}