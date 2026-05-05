package com.mtt.app.testing

import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.remote.llm.LlmService

/**
 * Test double for LlmService that returns predictable, valid translations.
 * Used for E2E testing without requiring actual LLM API calls.
 */
class FakeLlmService : LlmService {

    override suspend fun translate(config: LlmRequestConfig): TranslationResponse {
        val userMessage = config.messages.lastOrNull()?.content ?: ""

        // Extract content between <textarea> and </textarea>
        val textareaContent = extractTextareaContent(userMessage)

        // Count numbered items (pattern: "N. text" where N is a number)
        val numberedItems = countNumberedItems(textareaContent)

        // Generate properly formatted response
        val translatedContent = buildString {
            appendLine("<textarea>")
            for (i in 1..numberedItems) {
                appendLine("$i. 【测试译文】译文文本$i")
            }
            append("</textarea>")
        }

        return TranslationResponse(
            content = translatedContent,
            model = config.model.modelId,
            tokensUsed = numberedItems * 10
        )
    }

    override suspend fun testConnection(modelId: String): Boolean = true

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

    /**
     * Counts numbered items matching pattern "N. text" where N is a positive integer.
     */
    private fun countNumberedItems(content: String): Int {
        if (content.isBlank()) return 0

        val lines = content.split("\n")
        val numberPattern = Regex("^\\d+\\..+")

        return lines.count { line ->
            numberPattern.matches(line.trim())
        }
    }
}