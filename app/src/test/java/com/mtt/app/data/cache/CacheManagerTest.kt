package com.mtt.app.data.cache

import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for CacheManager.
 *
 * Tests cover:
 * - Save and retrieve cached translations
 * - Cache miss returns null
 * - Clear expired cache removes old entries
 * - Clear all cache removes everything
 * - Cache statistics reporting
 * - Export to JSON
 */
class CacheManagerTest {

    private val mockDao: CacheItemDao = mockk(relaxed = true)
    private val cacheManager = CacheManager(mockDao)

    // region getCached tests

    @Test
    fun `getCached returns cached translation on hit`() = runBlocking {
        val sourceText = "Hello"
        val modelId = "gpt-4"
        val cachedEntity = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 12345,
            status = TranslationStatus.TRANSLATED,
            sourceText = sourceText,
            translatedText = "Hola",
            model = modelId,
            batchIndex = 0
        )
        coEvery { mockDao.getByProjectId(any()) } returns listOf(cachedEntity)

        val result = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)

        assertNotNull(result)
        assertEquals("Hola", result!!.content)
        assertEquals(modelId, result.model)
    }

    @Test
    fun `getCached returns null on cache miss`() = runBlocking {
        coEvery { mockDao.getByProjectId(any()) } returns emptyList()

        val result = cacheManager.getCached("Unknown", TranslationMode.TRANSLATE, "gpt-4")

        assertNull(result)
    }

    @Test
    fun `getCached ignores untranslated status`() = runBlocking {
        val sourceText = "Hello"
        val modelId = "gpt-4"
        val untranslatedEntity = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 12345,
            status = TranslationStatus.UNTRANSLATED,
            sourceText = sourceText,
            translatedText = "Hola",
            model = modelId,
            batchIndex = 0
        )
        coEvery { mockDao.getByProjectId(any()) } returns listOf(untranslatedEntity)

        val result = cacheManager.getCached(sourceText, TranslationMode.TRANSLATE, modelId)

        assertNull(result)
    }

    // endregion

    // region saveToCache tests

    @Test
    fun `saveToCache inserts entity into DAO`() = runBlocking {
        val sourceText = "Hello"
        val translation = "Hola"
        val modelId = "gpt-4"

        cacheManager.saveToCache(sourceText, translation, TranslationMode.TRANSLATE, modelId)

        coVerify { mockDao.insert(match { entity ->
            entity.sourceText == sourceText &&
                entity.translatedText == translation &&
                entity.model == modelId &&
                entity.status == TranslationStatus.TRANSLATED
        }) }
    }

    // endregion

    // region clearExpiredCache tests

    @Test
    fun `clearExpiredCache removes old entries`() = runBlocking {
        val sourceText = "Old text"
        val modelId = "gpt-4"
        // Precompute textIndex that saveToCache will generate from this input
        val dedupHash = sha256Fix("$sourceText|TRANSLATE|$modelId")
        val computedTextIndex = hashToIntFix(dedupHash)

        val insertedEntity = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = computedTextIndex,
            status = TranslationStatus.TRANSLATED,
            sourceText = sourceText,
            translatedText = "Old translation",
            model = modelId,
            batchIndex = 0
        )
        // Mock insert to return the entity that will be retrieved by getByProjectId
        coEvery { mockDao.insert(any()) } returns Unit
        coEvery { mockDao.getByProjectId(any()) } returns listOf(insertedEntity)

        // Save to cache to track insertion time
        cacheManager.saveToCache(sourceText, "Old translation", TranslationMode.TRANSLATE, modelId)

        cacheManager.clearExpiredCache(Duration.ZERO)

        coVerify { mockDao.updateItemStatus(
            projectId = "translation_cache",
            textIndex = computedTextIndex,
            status = TranslationStatus.EXCLUDED,
            translatedText = "Old translation",
            model = modelId,
            batchIndex = 0
        ) }
    }

    // endregion

    // Helper functions mirroring CacheManager's private implementations
    private fun sha256Fix(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun hashToIntFix(hash: String): Int {
        val sub = hash.take(8)
        val value = sub.toLong(16)
        return (value and 0x7FFFFFFF).toInt()
    }

    // region clearExpiredCache keeps recent entries

    @Test
    fun `clearExpiredCache keeps recent entries`() = runBlocking {
        val recentEntity = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 200,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Recent text",
            translatedText = "Recent translation",
            model = "gpt-4",
            batchIndex = 0
        )
        coEvery { mockDao.getByProjectId(any()) } returns listOf(recentEntity)

        cacheManager.saveToCache("Recent text", "Recent translation", TranslationMode.TRANSLATE, "gpt-4")

        // Clear with 1 day max age should keep recent entries
        cacheManager.clearExpiredCache(Duration.ofDays(1))

        coVerify(exactly = 0) { mockDao.updateItemStatus(
            projectId = any(),
            textIndex = 200,
            status = any(),
            translatedText = any(),
            model = any(),
            batchIndex = any()
        ) }
    }

    // endregion

    // region clearAllCache tests

    @Test
    fun `clearAllCache deletes all entries`() = runBlocking {
        cacheManager.clearAllCache()

        coVerify { mockDao.deleteByProjectId("translation_cache") }
    }

    @Test
    fun `clearAllCache resets statistics`() = runBlocking {
        // First, trigger some hits and misses
        coEvery { mockDao.getByProjectId(any()) } returns emptyList()
        cacheManager.getCached("test", TranslationMode.TRANSLATE, "gpt-4")

        // Clear cache
        cacheManager.clearAllCache()

        // Stats should be reset
        val stats = cacheManager.getCacheStats()
        assertEquals(0L, stats.hitCount)
        assertEquals(0L, stats.missCount)
    }

    // endregion

    // region getCacheStats tests

    @Test
    fun `getCacheStats returns correct total entries`() = runBlocking {
        coEvery { mockDao.countTotal(any()) } returns 5

        val stats = cacheManager.getCacheStats()

        assertEquals(5, stats.totalEntries)
    }

    @Test
    fun `getCacheStats calculates hit rate correctly`() = runBlocking {
        coEvery { mockDao.countTotal(any()) } returns 0

        val hitEntity = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 1,
            status = TranslationStatus.TRANSLATED,
            sourceText = "hit",
            translatedText = "translated_hit",
            model = "gpt-4",
            batchIndex = 0
        )
        // First 3 calls return a matching entity (hit), 4th returns empty (miss)
        coEvery { mockDao.getByProjectId("translation_cache") } returnsMany listOf(
            listOf(hitEntity),
            listOf(hitEntity),
            listOf(hitEntity),
            emptyList()
        )

        // Trigger 3 hits and 1 miss = 75% hit rate
        repeat(3) { cacheManager.getCached("hit", TranslationMode.TRANSLATE, "gpt-4") }
        cacheManager.getCached("miss", TranslationMode.TRANSLATE, "gpt-4")

        val stats = cacheManager.getCacheStats()

        assertEquals(0.75, stats.hitRate, 0.001)
        assertEquals(3L, stats.hitCount)
        assertEquals(1L, stats.missCount)
    }

    // endregion

    // region exportToJson tests

    @Test
    fun `exportToJson returns source to translated mapping`() = runBlocking {
        val entity1 = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 1,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Hello",
            translatedText = "Hola",
            model = "gpt-4",
            batchIndex = 0
        )
        val entity2 = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 2,
            status = TranslationStatus.TRANSLATED,
            sourceText = "World",
            translatedText = "Mundo",
            model = "gpt-4",
            batchIndex = 0
        )
        coEvery { mockDao.getByProjectId(any()) } returns listOf(entity1, entity2)

        val result = cacheManager.exportToJson()

        assertEquals(2, result.size)
        assertEquals("Hola", result["Hello"])
        assertEquals("Mundo", result["World"])
    }

    @Test
    fun `exportToJson filters out blank translations`() = runBlocking {
        val entity1 = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 1,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Hello",
            translatedText = "Hola",
            model = "gpt-4",
            batchIndex = 0
        )
        val entity2 = CacheItemEntity(
            projectId = "translation_cache",
            textIndex = 2,
            status = TranslationStatus.TRANSLATED,
            sourceText = "Blank",
            translatedText = "   ",
            model = "gpt-4",
            batchIndex = 0
        )
        coEvery { mockDao.getByProjectId(any()) } returns listOf(entity1, entity2)

        val result = cacheManager.exportToJson()

        assertEquals(1, result.size)
        assertTrue(result.containsKey("Hello"))
        assertTrue(result.containsKey("Blank").not())
    }

    // endregion
}
