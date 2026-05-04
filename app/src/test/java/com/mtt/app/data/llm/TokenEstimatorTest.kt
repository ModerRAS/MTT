package com.mtt.app.data.llm

import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TokenEstimator.
 */
class TokenEstimatorTest {

    private fun createOpenAiModel(contextWindow: Int) = ModelInfo(
        modelId = "test-model",
        displayName = "Test Model",
        contextWindow = contextWindow,
        provider = LlmProvider.OpenAI(apiKey = "test-key")
    )

    // region estimate() tests

    @Test
    fun estimate_emptyString_returnsZero() {
        val result = TokenEstimator.estimate("")
        assertEquals(0, result)
    }

    @Test
    fun estimate_asciiText_returnsCorrectEstimation() {
        // 4 ASCII chars = 1 token
        // 16 ASCII chars = 4 tokens
        val result = TokenEstimator.estimate("Hello, World!")
        assertEquals(4, result)
    }

    @Test
    fun estimate_cjkText_returnsCorrectEstimation() {
        // 2 CJK chars = 1 token
        // 4 CJK chars = 2 tokens
        val result = TokenEstimator.estimate("你好世界")
        assertEquals(2, result)
    }

    @Test
    fun estimate_mixedText_returnsCorrectEstimation() {
        // "Hello你好" = 5 ASCII + 2 CJK
        // ASCII tokens: 5/4 = 1.25
        // CJK tokens: 2/2 = 1.0
        // Total: ceil(2.25) = 3
        val result = TokenEstimator.estimate("Hello你好")
        assertEquals(3, result)
    }

    @Test
    fun estimate_pureAsciiLongText_returnsCorrectEstimation() {
        // 45 ASCII chars, 45/4 = 11.25, ceil = 12
        val text = "The quick brown fox jumps over the lazy dog. "
        val result = TokenEstimator.estimate(text)
        assertEquals(12, result)
    }

    @Test
    fun estimate_koreanText_returnsCorrectEstimation() {
        // Korean characters (ac00-d7af) = 2 chars/token
        // "안녕하세요" = 5 chars, ceil(5/2) = 3
        val result = TokenEstimator.estimate("안녕하세요")
        assertEquals(3, result)
    }

    // endregion

    // region estimateBatch() tests

    @Test
    fun estimateBatch_singleText_calculatesCorrectTotal() {
        // textTokens = estimate("Hello") = ceil(5/4) = 2
        // systemTokens = estimate("System prompt") = ceil(13/4) = 4
        // prefixTokens = 1 * estimate("Translate: ") = ceil(11/4) = 3
        // overhead = 100
        // Total = 2 + 4 + 3 + 100 = 109
        val result = TokenEstimator.estimateBatch(
            texts = listOf("Hello"),
            systemPrompt = "System prompt",
            userPromptPrefix = "Translate: "
        )
        assertEquals(109, result)
    }

    @Test
    fun estimateBatch_multipleTexts_sumsAllComponents() {
        // textTokens = estimate("Hi") + estimate("Bye") = ceil(2/4) + ceil(3/4) = 1 + 1 = 2
        // systemTokens = estimate("System") = ceil(6/4) = 2
        // prefixTokens = 2 * estimate("Pre: ") = 2 * ceil(5/4) = 2 * 2 = 4
        // overhead = 100
        // Total = 2 + 2 + 4 + 100 = 108
        val result = TokenEstimator.estimateBatch(
            texts = listOf("Hi", "Bye"),
            systemPrompt = "System",
            userPromptPrefix = "Pre: "
        )
        assertEquals(108, result)
    }

    @Test
    fun estimateBatch_emptyTextsList_calculatesWithoutTextTokens() {
        // textTokens = 0
        // systemTokens = estimate("System") = 2
        // prefixTokens = 0 * anything = 0
        // overhead = 100
        // Total = 0 + 2 + 0 + 100 = 102
        val result = TokenEstimator.estimateBatch(
            texts = emptyList(),
            systemPrompt = "System",
            userPromptPrefix = "Translate: "
        )
        assertEquals(102, result)
    }

    // endregion

    // region canFitInContext() tests

    @Test
    fun canFitInContext_withinLimit_returnsTrue() {
        val model = createOpenAiModel(8000)
        // 7199 < 8000 * 0.9 = 7200 → true
        assertTrue(TokenEstimator.canFitInContext(7199, model))
    }

    @Test
    fun canFitInContext_atExactLimit_returnsFalse() {
        val model = createOpenAiModel(8000)
        // 7200 < 7200 → false
        assertFalse(TokenEstimator.canFitInContext(7200, model))
    }

    @Test
    fun canFitInContext_overLimit_returnsFalse() {
        val model = createOpenAiModel(8000)
        // 7500 < 7200 → false
        assertFalse(TokenEstimator.canFitInContext(7500, model))
    }

    @Test
    fun canFitInContext_at90PercentBufferEdge_returnsTrue() {
        val model = createOpenAiModel(16000)
        // 90% of 16000 = 14400, so 14399 should fit
        assertTrue(TokenEstimator.canFitInContext(14399, model))
    }

    @Test
    fun canFitInContext_at91Percent_returnsFalse() {
        val model = createOpenAiModel(16000)
        // 91% of 16000 = 14560
        assertFalse(TokenEstimator.canFitInContext(14560, model))
    }

    @Test
    fun canFitInContext_zeroTokens_returnsTrue() {
        val model = createOpenAiModel(1000)
        // 0 < 900 → true
        assertTrue(TokenEstimator.canFitInContext(0, model))
    }

    // endregion
}