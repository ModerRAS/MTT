package com.mtt.app.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.local.AppDatabase
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * E2E pipeline test verifying the full translation flow
 * using FakeLlmService + real CacheManager (Room in-memory).
 *
 * Tests:
 * - FakeLlmService translates 50 items correctly
 * - CacheManager persists and retrieves translations
 * - JSON export produces correct mapping
 * - Batch resume: 20 items → then 30 more
 */
@RunWith(RobolectricTestRunner::class)
class E2EPipelineTest {

    private lateinit var database: AppDatabase
    private lateinit var cacheManager: CacheManager
    private lateinit var fakeLlmService: FakeLlmService

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        cacheManager = CacheManager(database.cacheItemDao())
        fakeLlmService = FakeLlmService()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ─────────────────────────────────────────────
    //  Build helpers
    // ─────────────────────────────────────────────

    private fun buildTextareaContent(items: List<Pair<Int, String>>): String {
        return buildString {
            appendLine("<textarea>")
            items.forEach { (idx, text) ->
                appendLine("$idx. $text")
            }
            append("</textarea>")
        }
    }

    private fun buildLlmConfig(items: List<Pair<Int, String>>): LlmRequestConfig {
        return LlmRequestConfig(
            messages = listOf(
                LlmRequestConfig.Message("user", buildTextareaContent(items))
            ),
            systemPrompt = "你是翻译助手",
            model = com.mtt.app.data.remote.llm.ModelRegistry.GPT_4O_MINI
        )
    }

    // ─────────────────────────────────────────────
    //  Test 1: FakeLlmService basic translation
    // ─────────────────────────────────────────────

    @Test
    fun `FakeLlmService translates single item`() = runBlocking {
        val item = listOf(1 to "Hello World")
        val config = buildLlmConfig(item)
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        assertTrue(response.content.contains("<textarea>"))
        assertTrue(response.content.contains("1. 【测试译文】译文文本1"))
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(10, response.tokensUsed)
    }

    @Test
    fun `FakeLlmService translates multiple items`() = runBlocking {
        val items = (1..5).map { it to "Source text $it" }
        val config = buildLlmConfig(items)
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        val content = response.content
        for (i in 1..5) {
            assertTrue("Missing item $i", content.contains("$i. 【测试译文】译文文本$i"))
        }
        assertEquals(50, response.tokensUsed)
    }

    @Test
    fun `FakeLlmService handles empty input`() = runBlocking {
        val config = buildLlmConfig(emptyList())
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        assertEquals("<textarea>\n</textarea>", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `FakeLlmService testConnection returns true`() = runBlocking {
        assertTrue(fakeLlmService.testConnection("gpt-4o-mini"))
    }

    // ─────────────────────────────────────────────
    //  Test 2: CacheManager persistence flow
    // ─────────────────────────────────────────────

    @Test
    fun `save and retrieve cached translations`() = runBlocking {
        val sourceText = "Hello"
        val translation = "你好"
        val modelId = "gpt-4o-mini"

        cacheManager.saveToCache(sourceText, translation, TranslationMode.TRANSLATE, modelId)

        val cached = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)
        assertNotNull("Cached item should exist", cached)
        assertEquals(translation, cached!!.content)
        assertEquals(modelId, cached.model)
    }

    @Test
    fun `exportToJson returns correct mapping`() = runBlocking {
        // Save 5 translations
        val entries = listOf(
            "Hello" to "你好",
            "World" to "世界",
            "Good morning" to "早上好",
            "Thank you" to "谢谢",
            "Goodbye" to "再见"
        )
        entries.forEach { (src, tgt) ->
            cacheManager.saveToCache(src, tgt, TranslationMode.TRANSLATE, "gpt-4o-mini")
        }

        val exported = cacheManager.exportToJson()
        assertEquals(5, exported.size)
        entries.forEach { (src, tgt) ->
            assertEquals("Key '$src' should map to '$tgt'", tgt, exported[src])
        }
    }

    @Test
    fun `exportToJson filters out blank translations`() = runBlocking {
        cacheManager.saveToCache("Hello", "你好", TranslationMode.TRANSLATE, "gpt-4o-mini")
        cacheManager.saveToCache("Blank1", "", TranslationMode.TRANSLATE, "gpt-4o-mini")
        cacheManager.saveToCache("Blank2", "   ", TranslationMode.TRANSLATE, "gpt-4o-mini")
        cacheManager.saveToCache("World", "世界", TranslationMode.TRANSLATE, "gpt-4o-mini")

        val exported = cacheManager.exportToJson()
        assertEquals(2, exported.size)
        assertTrue("Should have Hello", exported.containsKey("Hello"))
        assertTrue("Should have World", exported.containsKey("World"))
        assertTrue("Should NOT have Blank1", !exported.containsKey("Blank1"))
        assertTrue("Should NOT have Blank2", !exported.containsKey("Blank2"))
    }

    @Test
    fun `cache stats reflect hits and misses`() = runBlocking {
        cacheManager.saveToCache("Hello", "你好", TranslationMode.TRANSLATE, "gpt-4o-mini")

        cacheManager.getCached("Hello", TranslationMode.TRANSLATE, "gpt-4o-mini") // hit
        cacheManager.getCached("Unknown", TranslationMode.TRANSLATE, "gpt-4o-mini") // miss

        val stats = cacheManager.getCacheStats()
        assertEquals(1L, stats.hitCount)
        assertEquals(1L, stats.missCount)
        assertEquals(1, stats.totalEntries)
    }

    // ─────────────────────────────────────────────
    //  Test 3: 50-item full pipeline simulation
    // ─────────────────────────────────────────────

    @Test
    fun `50 item translation pipeline end-to-end`() = runBlocking {
        // Generate 50 source texts
        val sourceTexts = (1..50).map { idx ->
            "Source text number $idx for translation testing"
        }
        val modelId = "gpt-4o-mini"

        // Step 1: Translate all 50 items through FakeLlmService
        val buildRequest = { items: List<Pair<Int, String>> ->
            buildLlmConfig(items)
        }
        val allItems = sourceTexts.mapIndexed { idx, text -> (idx + 1) to text }

        val response = fakeLlmService.translate(buildRequest(allItems))

        // Step 2: Verify response has 50 numbered translations
        val content = response.content
        for (i in 1..50) {
            assertTrue("Response should contain item $i", content.contains("$i. 【测试译文】译文文本$i"))
        }
        assertEquals(500, response.tokensUsed)

        // Step 3: Save all 50 translations to CacheManager
        sourceTexts.forEachIndexed { idx, sourceText ->
            val translatedText = "【测试译文】译文文本${idx + 1}"
            cacheManager.saveToCache(sourceText, translatedText, TranslationMode.TRANSLATE, modelId)
        }

        // Step 4: Verify all 50 items are in cache
        sourceTexts.forEachIndexed { idx, sourceText ->
            val cached = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)
            assertNotNull("Item $idx should be cached", cached)
            assertEquals("Translation should match", "【测试译文】译文文本${idx + 1}", cached!!.content)
        }

        // Step 5: Verify JSON export
        val exported = cacheManager.exportToJson()
        assertEquals(50, exported.size)
        sourceTexts.forEachIndexed { idx, sourceText ->
            assertTrue("Export should contain source text $idx", exported.containsKey(sourceText))
            assertEquals(
                "Export should have correct translation",
                "【测试译文】译文文本${idx + 1}",
                exported[sourceText]
            )
        }

        // Step 6: Verify cache stats
        val stats = cacheManager.getCacheStats()
        assertEquals(50, stats.totalEntries)
        assertEquals(50L, stats.hitCount)
        assertEquals(0L, stats.missCount)
    }

    // ─────────────────────────────────────────────
    //  Test 4: Batch resume (20 then 30)
    // ─────────────────────────────────────────────

    @Test
    fun `batch resume 20 items then 30 more`() = runBlocking {
        val modelId = "gpt-4o-mini"

        // Phase 1: Translate first 20 items
        val batch1Texts = (1..20).map { idx -> "Batch1 text $idx" }
        val batch1Items = batch1Texts.mapIndexed { idx, text -> (idx + 1) to text }
        val response1 = fakeLlmService.translate(buildLlmConfig(batch1Items))

        // Verify response1 has 20 items
        for (i in 1..20) {
            assertTrue("Batch1 response should contain item $i", response1.content.contains("$i. 【测试译文】译文文本$i"))
        }

        // Save batch1 results
        batch1Texts.forEachIndexed { idx, sourceText ->
            val translatedText = "【测试译文】译文文本${idx + 1}"
            cacheManager.saveToCache(sourceText, translatedText, TranslationMode.TRANSLATE, modelId)
        }

        // Verify batch1 in cache
        assertEquals(20, cacheManager.getCacheStats().totalEntries)

        // Phase 2: Translate remaining 30 items (batch resume)
        val batch2Texts = (21..50).map { idx -> "Batch2 text $idx" }
        val batch2Items = batch2Texts.mapIndexed { idx, text -> (idx + 1) to text }
        val response2 = fakeLlmService.translate(buildLlmConfig(batch2Items))

        // Verify response2 has 30 items
        for (i in 1..30) {
            assertTrue("Batch2 response should contain item $i", response2.content.contains("$i. 【测试译文】译文文本$i"))
        }

        // Save batch2 results
        batch2Texts.forEachIndexed { idx, sourceText ->
            val translatedText = "【测试译文】译文文本${idx + 1}"
            cacheManager.saveToCache(sourceText, translatedText, TranslationMode.TRANSLATE, modelId)
        }

        // Verify: total 50 items in cache
        val stats = cacheManager.getCacheStats()
        assertEquals(50, stats.totalEntries)

        // Verify: all 50 items retrievable
        batch1Texts.forEachIndexed { idx, sourceText ->
            val cached = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)
            assertNotNull("Batch1 item $idx should be cached", cached)
        }
        batch2Texts.forEachIndexed { idx, sourceText ->
            val cached = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)
            assertNotNull("Batch2 item $idx should be cached", cached)
        }

        // Verify: JSON export has all 50
        val exported = cacheManager.exportToJson()
        assertEquals(50, exported.size)
    }

    // ─────────────────────────────────────────────
    //  Test 5: TRANSLATED status verification
    // ─────────────────────────────────────────────

    @Test
    fun `all saved translations have TRANSLATED status`() = runBlocking {
        val modelId = "gpt-4o-mini"

        repeat(50) { idx ->
            val sourceText = "Status check text $idx"
            val translatedText = "译文文本${idx + 1}"
            cacheManager.saveToCache(sourceText, translatedText, TranslationMode.TRANSLATE, modelId)
        }

        // All 50 items should be retrievable (only TRANSLATED/POLISHED are returned by getCached)
        repeat(50) { idx ->
            val sourceText = "Status check text $idx"
            val cached = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)
            assertNotNull("Item $idx should be cached with TRANSLATED status", cached)
            assertEquals("译文文本${idx + 1}", cached!!.content)
        }
    }
}
