package com.mtt.app

import com.mtt.app.data.model.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for core data models.
 */
class ModelTest {

    // ========== TranslationStatus Tests ==========

    @Test
    fun `TranslationStatus fromCode returns UNTRANSLATED for code 0`() {
        assertEquals(TranslationStatus.UNTRANSLATED, TranslationStatus.fromCode(0))
    }

    @Test
    fun `TranslationStatus fromCode returns TRANSLATED for code 1`() {
        assertEquals(TranslationStatus.TRANSLATED, TranslationStatus.fromCode(1))
    }

    @Test
    fun `TranslationStatus fromCode returns POLISHED for code 2`() {
        assertEquals(TranslationStatus.POLISHED, TranslationStatus.fromCode(2))
    }

    @Test
    fun `TranslationStatus fromCode returns EXCLUDED for code 7`() {
        assertEquals(TranslationStatus.EXCLUDED, TranslationStatus.fromCode(7))
    }

    @Test
    fun `TranslationStatus code values match AiNiee spec`() {
        assertEquals(0, TranslationStatus.UNTRANSLATED.code)
        assertEquals(1, TranslationStatus.TRANSLATED.code)
        assertEquals(2, TranslationStatus.POLISHED.code)
        assertEquals(7, TranslationStatus.EXCLUDED.code)
    }

    @Test
    fun `TranslationStatus all returns all status values`() {
        val allStatuses = TranslationStatus.all()
        assertEquals(4, allStatuses.size)
        assertTrue(allStatuses.contains(TranslationStatus.UNTRANSLATED))
        assertTrue(allStatuses.contains(TranslationStatus.TRANSLATED))
        assertTrue(allStatuses.contains(TranslationStatus.POLISHED))
        assertTrue(allStatuses.contains(TranslationStatus.EXCLUDED))
    }

    @Test
    fun `TranslationStatus fromCode throws for unknown code`() {
        try {
            TranslationStatus.fromCode(99)
            assertTrue(false, "Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("99") == true)
        }
    }

    // ========== TranslationMode Tests ==========

    @Test
    fun `TranslationMode has correct display names`() {
        assertEquals("翻译", TranslationMode.TRANSLATE.displayName)
        assertEquals("润色", TranslationMode.POLISH.displayName)
        assertEquals("校对", TranslationMode.PROOFREAD.displayName)
    }

    @Test
    fun `TranslationMode has all three modes`() {
        assertEquals(3, TranslationMode.entries.size)
    }

    // ========== LlmProvider Tests ==========

    @Test
    fun `LlmProvider OpenAI has default baseUrl`() {
        val openAI = LlmProvider.OpenAI()
        assertEquals("https://api.openai.com/v1", openAI.baseUrl)
    }

    @Test
    fun `LlmProvider Anthropic has default baseUrl`() {
        val anthropic = LlmProvider.Anthropic()
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
    }

    @Test
    fun `LlmProvider OpenAI can have custom baseUrl`() {
        val customUrl = "https://custom.api.endpoint.com/v1"
        val openAI = LlmProvider.OpenAI(customUrl)
        assertEquals(customUrl, openAI.baseUrl)
    }

    @Test
    fun `LlmProvider Anthropic can have custom baseUrl`() {
        val customUrl = "https://custom.anthropic.endpoint.com"
        val anthropic = LlmProvider.Anthropic(customUrl)
        assertEquals(customUrl, anthropic.baseUrl)
    }

    // ========== ModelInfo Tests ==========

    @Test
    fun `ModelInfo predefined models have correct values`() {
        assertEquals("gpt-4o", ModelInfo.GPT_4O.modelId)
        assertEquals("GPT-4o", ModelInfo.GPT_4O.displayName)
        assertEquals(128000, ModelInfo.GPT_4O.contextWindow)
        assertTrue(ModelInfo.GPT_4O.provider is LlmProvider.OpenAI)

        assertEquals("gpt-4o-mini", ModelInfo.GPT_4O_MINI.modelId)
        assertEquals("GPT-4o mini", ModelInfo.GPT_4O_MINI.displayName)
        assertEquals(128000, ModelInfo.GPT_4O_MINI.contextWindow)
        assertTrue(ModelInfo.GPT_4O_MINI.provider is LlmProvider.OpenAI)
        assertTrue(ModelInfo.GPT_4O_MINI.isDefault)

        assertEquals("claude-3-5-sonnet-20241022", ModelInfo.CLAUDE_SONNET.modelId)
        assertEquals("Claude 3.5 Sonnet", ModelInfo.CLAUDE_SONNET.displayName)
        assertEquals(200000, ModelInfo.CLAUDE_SONNET.contextWindow)
        assertTrue(ModelInfo.CLAUDE_SONNET.provider is LlmProvider.Anthropic)

        assertEquals("claude-3-5-haiku-20241007", ModelInfo.CLAUDE_HAIKU.modelId)
        assertEquals("Claude 3.5 Haiku", ModelInfo.CLAUDE_HAIKU.displayName)
        assertEquals(200000, ModelInfo.CLAUDE_HAIKU.contextWindow)
        assertTrue(ModelInfo.CLAUDE_HAIKU.provider is LlmProvider.Anthropic)
        assertTrue(ModelInfo.CLAUDE_HAIKU.isDefault)
    }

    @Test
    fun `ModelInfo ALL_MODELS contains all four models`() {
        assertEquals(4, ModelInfo.ALL_MODELS.size)
    }

    @Test
    fun `ModelInfo DEFAULT_MODELS contains two models`() {
        assertEquals(2, ModelInfo.DEFAULT_MODELS.size)
        assertTrue(ModelInfo.DEFAULT_MODELS.all { it.isDefault })
    }

    // ========== CacheItemEntity Tests ==========

    @Test
    fun `CacheItemEntity can be instantiated`() {
        val cacheItem = CacheItemEntity(
            projectId = "project-123",
            textIndex = 0,
            status = TranslationStatus.UNTRANSLATED,
            sourceText = "Hello",
            translatedText = "",
            model = "gpt-4o-mini",
            batchIndex = 0
        )
        assertEquals("project-123", cacheItem.projectId)
        assertEquals(0, cacheItem.textIndex)
        assertEquals(TranslationStatus.UNTRANSLATED, cacheItem.status)
        assertEquals("Hello", cacheItem.sourceText)
        assertEquals("gpt-4o-mini", cacheItem.model)
    }

    // ========== ProjectEntity Tests ==========

    @Test
    fun `ProjectEntity can be instantiated`() {
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            projectId = "project-456",
            name = "Test Project",
            sourceLang = "ja",
            targetLang = "zh",
            sourceFileUri = "content://uri",
            totalItems = 100,
            completedItems = 50,
            createdAt = now,
            updatedAt = now
        )
        assertEquals("project-456", project.projectId)
        assertEquals("Test Project", project.name)
        assertEquals(100, project.totalItems)
        assertEquals(50, project.completedItems)
    }

    // ========== GlossaryEntryEntity Tests ==========

    @Test
    fun `GlossaryEntryEntity can be instantiated with default values`() {
        val entry = GlossaryEntryEntity(
            projectId = "project-789",
            sourceTerm = "魔法",
            targetTerm = "magic"
        )
        assertEquals("EXACT", entry.matchType)
    }

    @Test
    fun `GlossaryEntryEntity can have custom match type`() {
        val entry = GlossaryEntryEntity(
            projectId = "project-789",
            sourceTerm = "test.*pattern",
            targetTerm = "replacement",
            matchType = "REGEX"
        )
        assertEquals("REGEX", entry.matchType)
    }

    // ========== TranslationProgress Tests ==========

    @Test
    fun `TranslationProgress percentage computed correctly`() {
        val progress = TranslationProgress(
            totalItems = 100,
            completedItems = 50,
            currentBatch = 5,
            totalBatches = 10,
            status = "Translating"
        )
        assertEquals(50, progress.percentage)
    }

    @Test
    fun `TranslationProgress percentage is 0 when total is 0`() {
        val progress = TranslationProgress(
            totalItems = 0,
            completedItems = 0,
            currentBatch = 0,
            totalBatches = 0,
            status = "Idle"
        )
        assertEquals(0, progress.percentage)
    }

    @Test
    fun `TranslationProgress percentage handles 100 percent`() {
        val progress = TranslationProgress(
            totalItems = 100,
            completedItems = 100,
            currentBatch = 10,
            totalBatches = 10,
            status = "Completed"
        )
        assertEquals(100, progress.percentage)
    }

    @Test
    fun `TranslationProgress initial creates zero progress`() {
        val progress = TranslationProgress.initial()
        assertEquals(0, progress.totalItems)
        assertEquals(0, progress.completedItems)
        assertEquals(0, progress.percentage)
        assertEquals("Idle", progress.status)
    }

    // ========== LlmRequestConfig Tests ==========

    @Test
    fun `LlmRequestConfig can be instantiated`() {
        val messages = listOf(
            LlmRequestConfig.Message("system", "You are a translator"),
            LlmRequestConfig.Message("user", "Translate this")
        )
        val config = LlmRequestConfig(
            messages = messages,
            systemPrompt = "Translate Japanese to Chinese",
            model = ModelInfo.GPT_4O_MINI,
            temperature = 0.5f,
            maxTokens = 2048
        )
        assertEquals(2, config.messages.size)
        assertEquals("Translate Japanese to Chinese", config.systemPrompt)
        assertEquals(0.5f, config.temperature)
        assertEquals(2048, config.maxTokens)
    }

    @Test
    fun `LlmRequestConfig has default values`() {
        val messages = listOf(LlmRequestConfig.Message("user", "Hello"))
        val config = LlmRequestConfig(
            messages = messages,
            systemPrompt = "Translate",
            model = ModelInfo.GPT_4O_MINI
        )
        assertEquals(0.7f, config.temperature)
        assertEquals(4096, config.maxTokens)
    }

    // ========== TranslationResponse Tests ==========

    @Test
    fun `TranslationResponse can be instantiated`() {
        val response = TranslationResponse(
            content = "Translated text",
            model = "gpt-4o-mini",
            tokensUsed = 150
        )
        assertEquals("Translated text", response.content)
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(150, response.tokensUsed)
    }

    // ========== TranslationUiState Tests ==========

    @Test
    fun `TranslationUiState Idle can be created`() {
        val state: TranslationUiState = TranslationUiState.Idle
        assertTrue(state is TranslationUiState.Idle)
    }

    @Test
    fun `TranslationUiState Loading can be created`() {
        val state: TranslationUiState = TranslationUiState.Loading("Loading project...")
        assertTrue(state is TranslationUiState.Loading)
        assertEquals("Loading project...", (state as TranslationUiState.Loading).message)
    }

    @Test
    fun `TranslationUiState Translating can be created`() {
        val progress = TranslationProgress.initial()
        val state: TranslationUiState = TranslationUiState.Translating(progress)
        assertTrue(state is TranslationUiState.Translating)
    }

    @Test
    fun `TranslationUiState Completed can be created`() {
        val state: TranslationUiState = TranslationUiState.Completed
        assertTrue(state is TranslationUiState.Completed)
    }

    @Test
    fun `TranslationUiState Error can be created`() {
        val state: TranslationUiState = TranslationUiState.Error("Network error")
        assertTrue(state is TranslationUiState.Error)
        assertEquals("Network error", (state as TranslationUiState.Error).message)
    }
}