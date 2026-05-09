package com.mtt.app.testing

import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationPair
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.remote.llm.LlmService

/**
 * Test double for LlmService that returns predictable, valid translations.
 * Used for E2E testing without requiring actual LLM API calls.
 *
 * @param simulateUntranslatedIndices Set of 0-based indices where the LLM "fails" to translate
 *                                    (returns source text as translation for those items).
 */
class FakeLlmService(
    private val simulateUntranslatedIndices: Set<Int> = emptySet()
) : LlmService {

    override suspend fun translate(config: LlmRequestConfig): TranslationResponse {
        // Extract source items from the user message (textarea content)
        val sourceItems = extractSourceItems(config)

        // Build translation pairs
        val translationPairs = sourceItems.mapIndexed { index, source ->
            val translated = if (index in simulateUntranslatedIndices) {
                // Simulate LLM not translating this item
                source
            } else {
                // Simulate successful translation - use index for predictable output
                "【测试译文】译文文本${index + 1}"
            }
            TranslationPair(source = source, translated = translated)
        }

        // Generate textarea-formatted content (backward compatibility)
        val translatedContent = buildString {
            appendLine("<textarea>")
            translationPairs.forEachIndexed { index, pair ->
                appendLine("${index + 1}. ${pair.translated}")
            }
            append("</textarea>")
        }

        return TranslationResponse(
            content = translatedContent,
            model = config.model.modelId,
            tokensUsed = sourceItems.size * 10,
            inputTokens = sourceItems.size * 5,
            outputTokens = sourceItems.size * 5,
            translations = translationPairs.map { it.translated },
            translationPairs = translationPairs
        )
    }

    override suspend fun testConnection(modelId: String): Boolean = true

    /**
     * Extracts numbered source items from the user message.
     * Pattern: "N. text" where N is a number starting from 1.
     */
    private fun extractSourceItems(config: LlmRequestConfig): List<String> {
        val userMessage = config.messages.lastOrNull()?.content ?: ""
        val textareaContent = extractTextareaContent(userMessage)

        if (textareaContent.isBlank()) return emptyList()

        val lines = textareaContent.split("\n")
        val numberPattern = Regex("^(\\d+)\\.\\s*(.*)$")

        return lines.mapNotNull { line ->
            val match = numberPattern.matchEntire(line.trim())
            match?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Extracts content between <textarea> and </textarea> tags.
     */
    private fun extractTextareaContent(message: String): String {
        val startTag = "<textarea>"
        val endTag = "</textarea>"

        val startIndex = message.indexOf(startTag)
        val endIndex = message.indexOf(endTag)

        return if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            message.substring(startIndex + startTag.length, endIndex).trim()
        } else {
            ""
        }
    }
}
