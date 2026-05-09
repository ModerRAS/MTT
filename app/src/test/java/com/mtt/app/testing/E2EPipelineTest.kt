package com.mtt.app.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.local.AppDatabase
import com.mtt.app.data.model.LlmRequestConfig
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationStatus
import com.mtt.app.domain.pipeline.TranslationVerifier
import com.mtt.app.domain.pipeline.VerificationResult
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
 * - Retry flow: verification detects untranslated items, retry recovers them
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
    fun `FakeLlmService translates single item with translationPairs`() = runBlocking {
        val item = listOf(1 to "Hello World")
        val config = buildLlmConfig(item)
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        // Verify translationPairs mode (new behavior)
        assertNotNull(response.translationPairs)
        assertEquals(1, response.translationPairs!!.size)
        assertEquals("Hello World", response.translationPairs[0].source)
        assertEquals("【测试译文】译文文本1", response.translationPairs[0].translated)
        // Backward compatibility: content still has textarea format
        assertTrue(response.content.contains("<textarea>"))
        assertTrue(response.content.contains("1. 【测试译文】译文文本1"))
        assertEquals("gpt-4o-mini", response.model)
        assertEquals(10, response.tokensUsed)
    }

    @Test
    fun `FakeLlmService translates multiple items with translationPairs`() = runBlocking {
        val items = (1..5).map { it to "Source text $it" }
        val config = buildLlmConfig(items)
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        // Verify translationPairs
        assertNotNull(response.translationPairs)
        assertEquals(5, response.translationPairs!!.size)
        for (i in 1..5) {
            assertTrue("Missing item $i in translationPairs",
                response.translationPairs.any { it.source == "Source text $i" && it.translated == "【测试译文】译文文本$i" })
        }
        // Verify backward compatibility content
        val content = response.content
        for (i in 1..5) {
            assertTrue("Missing item $i", content.contains("$i. 【测试译文】译文文本$i"))
        }
        assertEquals(50, response.tokensUsed)
    }

    @Test
    fun `FakeLlmService handles empty input with translationPairs`() = runBlocking {
        val config = buildLlmConfig(emptyList())
        val response = fakeLlmService.translate(config)

        assertNotNull(response)
        // translationPairs should be empty
        assertNotNull(response.translationPairs)
        assertTrue(response.translationPairs!!.isEmpty())
        // translations array should also be empty
        assertNotNull(response.translations)
        assertTrue(response.translations!!.isEmpty())
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

    // ─────────────────────────────────────────────
    //  Test 6: Retry flow with simulateUntranslatedIndices
    // ─────────────────────────────────────────────

    @Test
    fun `verification detects untranslated and retry recovers them`() = runBlocking {
        // Create FakeLlmService that "fails" to translate index 0 (first item)
        val fakeServiceWithFailure = FakeLlmService(simulateUntranslatedIndices = setOf(0))

        // Simulate what the pipeline does: translate items
        val items = listOf(
            1 to "Hello World",
            2 to "Good morning",
            3 to "Thank you"
        )
        val config = buildLlmConfig(items)
        val response = fakeServiceWithFailure.translate(config)

        // Verify translationPairs contains the expected results
        assertNotNull(response.translationPairs)
        assertEquals(3, response.translationPairs!!.size)

        // Build translation map as the pipeline does
        val translations = mutableMapOf<Int, String>()
        response.translationPairs!!.forEachIndexed { index, pair ->
            translations[index] = pair.translated
        }

        // Run verification (same logic as TranslationVerifier)
        val verifyItems = items.mapIndexed { idx, (_, text) -> idx to text }
        val result = TranslationVerifier.verify(verifyItems, translations)

        // Verification should detect that item 0 was not translated
        assertEquals("Should have 2 passed items", 2, result.passed.size)
        assertTrue("Items 1 and 2 should pass", result.passed.containsAll(listOf(1, 2)))
        assertEquals("Should have 1 failed item", 1, result.failed.size)
        assertEquals("Failed item should be at index 0", 0, result.failed[0].globalIndex)

        // Now simulate retry: create FakeLlmService that succeeds for index 0 this time
        // (In real pipeline, the same service would retry with different prompt)
        val fakeServiceSuccess = FakeLlmService(simulateUntranslatedIndices = emptySet())
        val retryResponse = fakeServiceSuccess.translate(buildLlmConfig(items))

        // Build retry translation map
        val retryTranslations = mutableMapOf<Int, String>()
        retryResponse.translationPairs!!.forEachIndexed { index, pair ->
            retryTranslations[index] = pair.translated
        }

        // Run verification again on all items
        val retryResult = TranslationVerifier.verify(verifyItems, retryTranslations)

        // All items should now pass (including the recovered item 0)
        assertEquals("Should have 3 passed items after retry", 3, retryResult.passed.size)
        assertEquals("Should have 0 failed items after retry", 0, retryResult.failed.size)
    }

    @Test
    fun `FakeLlmService with simulateUntranslatedIndices returns source as translation`() = runBlocking {
        // Item at index 2 should return source text unchanged
        val fakeService = FakeLlmService(simulateUntranslatedIndices = setOf(2))
        val items = listOf(
            1 to "First",
            2 to "Second",
            3 to "Third"
        )
        val response = fakeService.translate(buildLlmConfig(items))

        assertNotNull(response.translationPairs)
        assertEquals(3, response.translationPairs!!.size)

        // Index 0 and 1 should be translated
        assertEquals("First", response.translationPairs[0].source)
        assertEquals("【测试译文】译文文本1", response.translationPairs[0].translated)

        assertEquals("Second", response.translationPairs[1].source)
        assertEquals("【测试译文】译文文本2", response.translationPairs[1].translated)

        // Index 2 should return source text unchanged (simulating LLM failure)
        assertEquals("Third", response.translationPairs[2].source)
        assertEquals("Third", response.translationPairs[2].translated)
    }
}
