package com.mtt.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.ProjectDao
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.ProjectEntity
import com.mtt.app.data.model.TranslationStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for Room DAO operations using in-memory database.
 */
@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var database: AppDatabase
    private lateinit var projectDao: ProjectDao
    private lateinit var cacheItemDao: CacheItemDao
    private lateinit var glossaryDao: GlossaryDao

    private val testProjectId = "test-project-001"
    private val testTimestamp = System.currentTimeMillis()

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()

        projectDao = database.projectDao()
        cacheItemDao = database.cacheItemDao()
        glossaryDao = database.glossaryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // ========== ProjectDao Tests ==========

    @Test
    fun projectDao_insertAndGetById() = runTest {
        val project = createTestProject()

        projectDao.insert(project)
        val retrieved = projectDao.getById(testProjectId)

        assertNotNull(retrieved)
        assertEquals(testProjectId, retrieved.projectId)
        assertEquals("Test Project", retrieved.name)
        assertEquals("ja", retrieved.sourceLang)
        assertEquals("zh", retrieved.targetLang)
        assertEquals(100, retrieved.totalItems)
        assertEquals(0, retrieved.completedItems)
    }

    @Test
    fun projectDao_insertAndGetAll() = runTest {
        projectDao.insert(createTestProject())
        projectDao.insert(createTestProject().copy(projectId = "test-project-002"))

        val all = projectDao.getAll()

        assertEquals(2, all.size)
    }

    @Test
    fun projectDao_updateProgress() = runTest {
        projectDao.insert(createTestProject())

        projectDao.updateProgress(testProjectId, 50, testTimestamp)
        val project = projectDao.getById(testProjectId)

        assertEquals(50, project?.completedItems)
        assertEquals(testTimestamp, project?.updatedAt)
    }

    @Test
    fun projectDao_delete() = runTest {
        projectDao.insert(createTestProject())
        projectDao.delete(createTestProject())

        val retrieved = projectDao.getById(testProjectId)
        assertNull(retrieved)
    }

    // ========== CacheItemDao Tests ==========

    @Test
    fun cacheItemDao_insertAndGetByProjectId() = runTest {
        val items = createTestCacheItems(3)
        cacheItemDao.insertAll(items)

        val retrieved = cacheItemDao.getByProjectId(testProjectId)

        assertEquals(3, retrieved.size)
        assertEquals(0, retrieved[0].textIndex)
        assertEquals(TranslationStatus.UNTRANSLATED, retrieved[0].status)
    }

    @Test
    fun cacheItemDao_insertAll_batchOperation() = runTest {
        val items = createTestCacheItems(10)
        cacheItemDao.insertAll(items)

        val count = cacheItemDao.countTotal(testProjectId)
        assertEquals(10, count)
    }

    @Test
    fun cacheItemDao_getByStatus() = runTest {
        val items = createTestCacheItems(5)
        cacheItemDao.insertAll(items)

        // Update one item to TRANSLATED
        cacheItemDao.updateItemStatus(
            projectId = testProjectId,
            textIndex = 2,
            status = TranslationStatus.TRANSLATED,
            translatedText = "Translated text",
            model = "gpt-4o-mini",
            batchIndex = 1
        )

        val translated = cacheItemDao.getByStatus(testProjectId, TranslationStatus.TRANSLATED)
        assertEquals(1, translated.size)
        assertEquals(2, translated[0].textIndex)
    }

    @Test
    fun cacheItemDao_getUntranslatedItems() = runTest {
        val items = createTestCacheItems(5)
        cacheItemDao.insertAll(items)

        // Mark item 3 as translated
        cacheItemDao.updateItemStatus(
            projectId = testProjectId,
            textIndex = 3,
            status = TranslationStatus.TRANSLATED,
            translatedText = "Translated",
            model = "gpt-4o-mini",
            batchIndex = 1
        )

        val untranslated = cacheItemDao.getUntranslatedItems(testProjectId)

        assertEquals(4, untranslated.size)
        assertTrue(untranslated.all { it.status == TranslationStatus.UNTRANSLATED || it.status == TranslationStatus.EXCLUDED })
    }

    @Test
    fun cacheItemDao_getCompletedItems() = runTest {
        val items = createTestCacheItems(5)
        cacheItemDao.insertAll(items)

        // Mark items as translated/polished
        cacheItemDao.updateItemStatus(testProjectId, 1, TranslationStatus.TRANSLATED, "T1", "gpt-4o-mini", 1)
        cacheItemDao.updateItemStatus(testProjectId, 3, TranslationStatus.POLISHED, "T3", "gpt-4o-mini", 1)

        val completed = cacheItemDao.getCompletedItems(testProjectId)

        assertEquals(2, completed.size)
    }

    @Test
    fun cacheItemDao_countByStatus() = runTest {
        val items = createTestCacheItems(5)
        cacheItemDao.insertAll(items)

        cacheItemDao.updateItemStatus(testProjectId, 2, TranslationStatus.TRANSLATED, "T", "gpt-4o-mini", 1)

        val untranslatedCount = cacheItemDao.countByStatus(testProjectId, TranslationStatus.UNTRANSLATED)
        val translatedCount = cacheItemDao.countByStatus(testProjectId, TranslationStatus.TRANSLATED)

        assertEquals(4, untranslatedCount)
        assertEquals(1, translatedCount)
    }

    @Test
    fun cacheItemDao_batchUpdate() = runTest {
        val items = createTestCacheItems(3)
        cacheItemDao.insertAll(items)

        val updatedItems = items.map { it.copy(status = TranslationStatus.TRANSLATED, translatedText = "Done") }
        cacheItemDao.batchUpdate(updatedItems)

        val count = cacheItemDao.countByStatus(testProjectId, TranslationStatus.TRANSLATED)
        assertEquals(3, count)
    }

    @Test
    fun cacheItemDao_deleteByProjectId() = runTest {
        cacheItemDao.insertAll(createTestCacheItems(5))

        cacheItemDao.deleteByProjectId(testProjectId)

        val count = cacheItemDao.countTotal(testProjectId)
        assertEquals(0, count)
    }

    // ========== GlossaryDao Tests ==========

    @Test
    fun glossaryDao_insertAndGetByProjectId() = runTest {
        val entries = createTestGlossaryEntries(3)
        glossaryDao.insertAll(entries)

        val retrieved = glossaryDao.getByProjectId(testProjectId)

        assertEquals(3, retrieved.size)
        assertEquals("魔法", retrieved[0].sourceTerm)
        assertEquals("magic", retrieved[0].targetTerm)
    }

    @Test
    fun glossaryDao_countByProjectId() = runTest {
        glossaryDao.insertAll(createTestGlossaryEntries(5))

        val count = glossaryDao.countByProjectId(testProjectId)
        assertEquals(5, count)
    }

    @Test
    fun glossaryDao_deleteByProjectId() = runTest {
        glossaryDao.insertAll(createTestGlossaryEntries(3))

        glossaryDao.deleteByProjectId(testProjectId)

        val count = glossaryDao.countByProjectId(testProjectId)
        assertEquals(0, count)
    }

    // ========== Helper Methods ==========

    private fun createTestProject(): ProjectEntity {
        return ProjectEntity(
            projectId = testProjectId,
            name = "Test Project",
            sourceLang = "ja",
            targetLang = "zh",
            sourceFileUri = "content://test/file.json",
            totalItems = 100,
            completedItems = 0,
            createdAt = testTimestamp,
            updatedAt = testTimestamp
        )
    }

    private fun createTestCacheItems(count: Int): List<CacheItemEntity> {
        return (0 until count).map { index ->
            CacheItemEntity(
                projectId = testProjectId,
                textIndex = index,
                status = TranslationStatus.UNTRANSLATED,
                sourceText = "Source text $index",
                translatedText = "",
                model = "",
                batchIndex = 0
            )
        }
    }

    private fun createTestGlossaryEntries(count: Int): List<GlossaryEntryEntity> {
        val terms = listOf(
            "魔法" to "magic",
            "剣" to "sword",
            "勇者" to "hero",
            "魔王" to "demon lord",
            "村庄" to "village"
        )
        return (0 until minOf(count, terms.size)).map { index ->
            GlossaryEntryEntity(
                projectId = testProjectId,
                sourceTerm = terms[index].first,
                targetTerm = terms[index].second,
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
            )
        }
    }
}
