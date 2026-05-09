package com.mtt.app.testing

import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FakeLlmService].
 */
class FakeLlmServiceTest {

    private val testModel = ModelInfo(
        modelId = "test-model",
        displayName = "Test Model",
        contextWindow = 128000,
        provider = LlmProvider.OpenAI("", "")
    )

    @Test
    fun `translate returns translationPairs with correct source and translated text`() = runBlocking {
        // Given: a request with 3 numbered items
        val request = LlmRequestConfig(
            messages = listOf(
                LlmRequestConfig.Message(
                    role = "user",
                    content = """
                        Please translate the following items:

                        <textarea>
                        1. 你好世界
                        2. 这是一个测试
                        3. 你好吗
                        </textarea>
                    """.trimIndent()
                )
            ),
            systemPrompt = "Translate to English",
            model = testModel
        )

        val fakeService = FakeLlmService()

        // When
        val response = fakeService.translate(request)

        // Then
        assertNotNull(response.translationPairs)
        assertEquals(3, response.translationPairs!!.size)

        // Verify source texts
        assertEquals("你好世界", response.translationPairs[0].source)
        assertEquals("这是一个测试", response.translationPairs[1].source)
        assertEquals("你好吗", response.translationPairs[2].source)

        // Verify translations (not untranslated)
        assertTrue(response.translationPairs[0].translated.contains("测试译文"))
        assertTrue(response.translationPairs[1].translated.contains("测试译文"))
        assertTrue(response.translationPairs[2].translated.contains("测试译文"))

        // Verify content field still has textarea format
        assertTrue(response.content.contains("<textarea>"))
        assertTrue(response.content.contains("</textarea>"))

        // Verify other fields
        assertEquals("test-model", response.model)
        assertEquals(30, response.tokensUsed)
        assertEquals(15, response.inputTokens)
        assertEquals(15, response.outputTokens)
        assertEquals(3, response.translations!!.size)
    }

    @Test
    fun `simulateUntranslatedIndices marks specific items as untranslated`() = runBlocking {
        // Given: a request with 5 numbered items, simulate items at indices 1 and 3 untranslated
        val request = LlmRequestConfig(
            messages = listOf(
                LlmRequestConfig.Message(
                    role = "user",
                    content = """
                        <textarea>
                        1. 第一项
                        2. 第二项
                        3. 第三项
                        4. 第四项
                        5. 第五项
                        </textarea>
                    """.trimIndent()
                )
            ),
            systemPrompt = "Translate",
            model = testModel
        )

        // Indices 1 and 3 should be "untranslated" (returned as source)
        val fakeService = FakeLlmService(simulateUntranslatedIndices = setOf(1, 3))

        // When
        val response = fakeService.translate(request)

        // Then
        assertNotNull(response.translationPairs)
        assertEquals(5, response.translationPairs!!.size)

        // Index 0: translated normally (uses index+1 for output, not source text)
        assertTrue(response.translationPairs[0].translated.contains("测试译文"))
        assertEquals("【测试译文】译文文本1", response.translationPairs[0].translated)

        // Index 1: untranslated (simulated failure)
        assertEquals("第二项", response.translationPairs[1].source)
        assertEquals("第二项", response.translationPairs[1].translated) // Same as source

        // Index 2: translated normally
        assertTrue(response.translationPairs[2].translated.contains("测试译文"))

        // Index 3: untranslated (simulated failure)
        assertEquals("第四项", response.translationPairs[3].source)
        assertEquals("第四项", response.translationPairs[3].translated) // Same as source

        // Index 4: translated normally
        assertTrue(response.translationPairs[4].translated.contains("测试译文"))
    }
}
