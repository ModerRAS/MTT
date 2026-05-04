package com.mtt.app

import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for data model classes.
 */
class ModelTest {

    // ═══════════════════════════════════════════════
    //  TranslationStatus
    // ═══════════════════════════════════════════════

    @Test
    fun `fromCode 0 returns UNTRANSLATED`() {
        val status = TranslationStatus.fromCode(0)
        assertSame(TranslationStatus.UNTRANSLATED, status)
        assertEquals(0, status.code)
    }

    @Test
    fun `fromCode 1 returns TRANSLATED`() {
        val status = TranslationStatus.fromCode(1)
        assertSame(TranslationStatus.TRANSLATED, status)
        assertEquals(1, status.code)
    }

    @Test
    fun `fromCode 2 returns POLISHED`() {
        val status = TranslationStatus.fromCode(2)
        assertSame(TranslationStatus.POLISHED, status)
        assertEquals(2, status.code)
    }

    @Test
    fun `fromCode 7 returns EXCLUDED`() {
        val status = TranslationStatus.fromCode(7)
        assertSame(TranslationStatus.EXCLUDED, status)
        assertEquals(7, status.code)
    }

    @Test
    fun `fromCode with invalid code throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TranslationStatus.fromCode(999)
        }
        assertEquals("Unknown translation status code: 999", exception.message)
    }

    @Test
    fun `all returns 4 items`() {
        val all = TranslationStatus.all()
        assertEquals(4, all.size)
        assertEquals(TranslationStatus.UNTRANSLATED, all[0])
        assertEquals(TranslationStatus.TRANSLATED, all[1])
        assertEquals(TranslationStatus.POLISHED, all[2])
        assertEquals(TranslationStatus.EXCLUDED, all[3])
    }

    // ═══════════════════════════════════════════════
    //  LlmProvider
    // ═══════════════════════════════════════════════

    @Test
    fun `OpenAI provider has correct default baseUrl`() {
        val provider = LlmProvider.OpenAI("test-key")
        assertEquals("test-key", provider.apiKey)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
    }

    @Test
    fun `OpenAI provider with custom baseUrl`() {
        val customUrl = "https://custom.openai.proxy/v1"
        val provider = LlmProvider.OpenAI("test-key", customUrl)
        assertEquals("test-key", provider.apiKey)
        assertEquals(customUrl, provider.baseUrl)
    }

    @Test
    fun `Anthropic provider has correct default baseUrl`() {
        val provider = LlmProvider.Anthropic("test-key")
        assertEquals("test-key", provider.apiKey)
        assertEquals("https://api.anthropic.com", provider.baseUrl)
    }

    @Test
    fun `Anthropic provider with custom baseUrl`() {
        val customUrl = "https://custom.anthropic.proxy"
        val provider = LlmProvider.Anthropic("test-key", customUrl)
        assertEquals("test-key", provider.apiKey)
        assertEquals(customUrl, provider.baseUrl)
    }

    // ═══════════════════════════════════════════════
    //  ModelInfo
    // ═══════════════════════════════════════════════

    @Test
    fun `ModelInfo has correct field values`() {
        val provider = LlmProvider.OpenAI("test-key")
        val modelInfo = ModelInfo(
            modelId = "gpt-4",
            displayName = "GPT-4",
            contextWindow = 8192,
            provider = provider
        )

        assertEquals("gpt-4", modelInfo.modelId)
        assertEquals("GPT-4", modelInfo.displayName)
        assertEquals(8192, modelInfo.contextWindow)
        assertSame(provider, modelInfo.provider)
    }

    @Test
    fun `ModelInfo equality with same modelId and provider`() {
        val provider = LlmProvider.OpenAI("test-key")
        val model1 = ModelInfo("gpt-4", "GPT-4", 8192, provider)
        val model2 = ModelInfo("gpt-4", "GPT-4", 8192, provider)

        assertEquals(model1, model2)
        assertEquals(model1.hashCode(), model2.hashCode())
    }

    @Test
    fun `ModelInfo inequality with different modelId`() {
        val provider = LlmProvider.OpenAI("test-key")
        val model1 = ModelInfo("gpt-4", "GPT-4", 8192, provider)
        val model2 = ModelInfo("gpt-3.5", "GPT-3.5", 4096, provider)

        assertNotEquals(model1, model2)
    }

    @Test
    fun `ModelInfo inequality with different provider`() {
        val provider1 = LlmProvider.OpenAI("key1")
        val provider2 = LlmProvider.Anthropic("key2")
        val model1 = ModelInfo("gpt-4", "GPT-4", 8192, provider1)
        val model2 = ModelInfo("gpt-4", "GPT-4", 8192, provider2)

        assertNotEquals(model1, model2)
    }

    // ═══════════════════════════════════════════════
    //  CacheItemEntity
    // ═══════════════════════════════════════════════

    @Test
    fun `CacheItemEntity has correct field values`() {
        val entity = CacheItemEntity(
            projectId = "project-123",
            textIndex = 5,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Hello",
            translatedText = "你好",
            model = "gpt-4",
            batchIndex = 1
        )

        assertEquals("project-123", entity.projectId)
        assertEquals(5, entity.textIndex)
        assertEquals(TranslationStatus.TRANSLATED, entity.status)
        assertEquals("Hello", entity.sourceText)
        assertEquals("你好", entity.translatedText)
        assertEquals("gpt-4", entity.model)
        assertEquals(1, entity.batchIndex)
    }

    @Test
    fun `CacheItemEntity composite primary key behavior`() {
        val entity1 = CacheItemEntity(
            projectId = "project-123",
            textIndex = 1,
            status = TranslationStatus.UNTRANSLATED,
            sourceText = "Text 1",
            translatedText = "",
            model = "gpt-4",
            batchIndex = 0
        )

        // Same primary key values should be equal
        val entity2 = CacheItemEntity(
            projectId = "project-123",
            textIndex = 1,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Text 1",
            translatedText = "Translated",
            model = "gpt-4",
            batchIndex = 1
        )

        // CacheItemEntity is a data class, so equals is based on all fields
        // Two entities with same projectId + textIndex but different other fields are not equal
        assertNotEquals(entity1, entity2)
    }

    @Test
    fun `CacheItemEntity equality with same values`() {
        val entity1 = CacheItemEntity(
            projectId = "project-123",
            textIndex = 5,
            status = TranslationStatus.POLISHED,
            sourceText = "Original",
            translatedText = "Polished",
            model = "claude-3",
            batchIndex = 2
        )

        val entity2 = CacheItemEntity(
            projectId = "project-123",
            textIndex = 5,
            status = TranslationStatus.POLISHED,
            sourceText = "Original",
            translatedText = "Polished",
            model = "claude-3",
            batchIndex = 2
        )

        assertEquals(entity1, entity2)
        assertEquals(entity1.hashCode(), entity2.hashCode())
    }

    @Test
    fun `CacheItemEntity can store all translation statuses`() {
        for (status in TranslationStatus.all()) {
            val entity = CacheItemEntity(
                projectId = "test-project",
                textIndex = 0,
                status = status,
                sourceText = "test",
                translatedText = "test",
                model = "test",
                batchIndex = 0
            )
            assertNotNull(entity)
            assertEquals(status, entity.status)
        }
    }
}
