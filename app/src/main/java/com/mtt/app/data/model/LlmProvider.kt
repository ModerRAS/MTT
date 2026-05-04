package com.mtt.app.data.model

/**
 * LLM provider interface and implementations:
 * - OpenAI: OpenAI GPT models
 * - Anthropic: Anthropic Claude models
 */
sealed interface LlmProvider {
    data class OpenAI(val apiKey: String, val baseUrl: String = "https://api.openai.com/v1") : LlmProvider
    data class Anthropic(val apiKey: String, val baseUrl: String = "https://api.anthropic.com") : LlmProvider
}
