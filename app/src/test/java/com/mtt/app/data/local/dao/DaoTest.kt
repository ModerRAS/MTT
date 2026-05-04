package com.mtt.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.data.local.AppDatabase
import com.mtt.app.data.local.Converters
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.TranslationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room DAO integration tests for [CacheItemDao] using an in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: CacheItemDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .build()
        dao = database.cacheItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestEntity(
        projectId: String = "project_1",
        textIndex: Int = 0,
        status: TranslationStatus = TranslationStatus.UNTRANSLATED,
        sourceText: String = "source text",
        translatedText: String = "translated text",
        model: String = "test-model",
        batchIndex: Int = 0
    ): CacheItemEntity {
        return CacheItemEntity(
            projectId = projectId,
            textIndex = textIndex,
            status = status,
            sourceText = sourceText,
            translatedText = translatedText,
            model = model,
            batchIndex = batchIndex
        )
    }

    // ═══════════════════════════════════════════════
    //  Insert Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `insert single item - item is retrievable`() {
        val item = createTestEntity(projectId = "p1", textIndex = 0)

        runBlocking {
            dao.insert(item)
            val results = dao.getByProjectId("p1")
            assertEquals(1, results.size)
            assertEquals("p1", results[0].projectId)
            assertEquals(0, results[0].textIndex)
            assertEquals(TranslationStatus.UNTRANSLATED, results[0].status)
        }
    }

    @Test
    fun `insertAll batch - count matches`() {
        val items = (0 until 5).map { i ->
            createTestEntity(projectId = "p_batch", textIndex = i)
        }

        runBlocking {
            dao.insertAll(items)
            val count = dao.countTotal("p_batch")
            assertEquals(5, count)
        }
    }

    // ═══════════════════════════════════════════════
    //  Query Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `getByProjectId - filters by projectId`() {
        val p1Items = listOf(
            createTestEntity(projectId = "project_a", textIndex = 0),
            createTestEntity(projectId = "project_a", textIndex = 1)
        )
        val p2Item = createTestEntity(projectId = "project_b", textIndex = 0)

        runBlocking {
            dao.insertAll(p1Items + p2Item)
            val results = dao.getByProjectId("project_a")
            assertEquals(2, results.size)
        }
    }

    @Test
    fun `getByStatus - filters by status code`() {
        val items = listOf(
            createTestEntity(projectId = "p_status", textIndex = 0, status = TranslationStatus.UNTRANSLATED),
            createTestEntity(projectId = "p_status", textIndex = 1, status = TranslationStatus.TRANSLATED),
            createTestEntity(projectId = "p_status", textIndex = 2, status = TranslationStatus.TRANSLATED)
        )

        runBlocking {
            dao.insertAll(items)
            val translated = dao.getByStatus("p_status", TranslationStatus.TRANSLATED)
            assertEquals(2, translated.size)
        }
    }

    @Test
    fun `getUntranslatedItems - returns items with status 0 or 7`() {
        val items = listOf(
            createTestEntity(projectId = "p_un", textIndex = 0, status = TranslationStatus.UNTRANSLATED),
            createTestEntity(projectId = "p_un", textIndex = 1, status = TranslationStatus.TRANSLATED),
            createTestEntity(projectId = "p_un", textIndex = 2, status = TranslationStatus.EXCLUDED)
        )

        runBlocking {
            dao.insertAll(items)
            val untranslated = dao.getUntranslatedItems("p_un")
            assertEquals(2, untranslated.size)
            assertTrue(untranslated.all { it.status.code == 0 || it.status.code == 7 })
        }
    }

    @Test
    fun `getCompletedItems - returns items with status 1 or 2`() {
        val items = listOf(
            createTestEntity(projectId = "p_comp", textIndex = 0, status = TranslationStatus.UNTRANSLATED),
            createTestEntity(projectId = "p_comp", textIndex = 1, status = TranslationStatus.TRANSLATED),
            createTestEntity(projectId = "p_comp", textIndex = 2, status = TranslationStatus.POLISHED),
            createTestEntity(projectId = "p_comp", textIndex = 3, status = TranslationStatus.EXCLUDED)
        )

        runBlocking {
            dao.insertAll(items)
            val completed = dao.getCompletedItems("p_comp")
            assertEquals(2, completed.size)
            assertTrue(completed.all { it.status.code == 1 || it.status.code == 2 })
        }
    }

    // ═══════════════════════════════════════════════
    //  Count Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `countByStatus - returns correct count`() {
        val items = listOf(
            createTestEntity(projectId = "p_count", textIndex = 0, status = TranslationStatus.TRANSLATED),
            createTestEntity(projectId = "p_count", textIndex = 1, status = TranslationStatus.TRANSLATED),
            createTestEntity(projectId = "p_count", textIndex = 2, status = TranslationStatus.UNTRANSLATED),
            createTestEntity(projectId = "p_count", textIndex = 3, status = TranslationStatus.POLISHED)
        )

        runBlocking {
            dao.insertAll(items)
            val translatedCount = dao.countByStatus("p_count", TranslationStatus.TRANSLATED)
            assertEquals(2, translatedCount)
        }
    }

    @Test
    fun `countTotal - returns total count for project`() {
        val items = (0 until 7).map { i ->
            createTestEntity(projectId = "p_total", textIndex = i)
        }

        runBlocking {
            dao.insertAll(items)
            val total = dao.countTotal("p_total")
            assertEquals(7, total)
        }
    }

    // ═══════════════════════════════════════════════
    //  Delete Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `deleteByProjectId - removes all items for project`() {
        val items = listOf(
            createTestEntity(projectId = "p_del", textIndex = 0),
            createTestEntity(projectId = "p_del", textIndex = 1),
            createTestEntity(projectId = "p_del", textIndex = 2)
        )

        runBlocking {
            dao.insertAll(items)
            dao.deleteByProjectId("p_del")
            val count = dao.countTotal("p_del")
            assertEquals(0, count)
        }
    }

    // ═══════════════════════════════════════════════
    //  Update Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `updateItemStatus - updates status translatedText model batchIndex`() {
        val item = createTestEntity(
            projectId = "p_upd",
            textIndex = 5,
            status = TranslationStatus.UNTRANSLATED,
            translatedText = "",
            model = "",
            batchIndex = 0
        )

        runBlocking {
            dao.insert(item)
            dao.updateItemStatus(
                projectId = "p_upd",
                textIndex = 5,
                status = TranslationStatus.TRANSLATED,
                translatedText = "已翻译文本",
                model = "gpt-4",
                batchIndex = 1
            )
            val results = dao.getByProjectId("p_upd")
            assertEquals(1, results.size)
            assertEquals(TranslationStatus.TRANSLATED, results[0].status)
            assertEquals("已翻译文本", results[0].translatedText)
            assertEquals("gpt-4", results[0].model)
            assertEquals(1, results[0].batchIndex)
        }
    }

    @Test
    fun `batchUpdate - updates multiple items atomically`() {
        val item1 = createTestEntity(
            projectId = "p_batch_upd",
            textIndex = 0,
            status = TranslationStatus.UNTRANSLATED,
            translatedText = ""
        )
        val item2 = createTestEntity(
            projectId = "p_batch_upd",
            textIndex = 1,
            status = TranslationStatus.UNTRANSLATED,
            translatedText = ""
        )
        val item3 = createTestEntity(
            projectId = "p_batch_upd",
            textIndex = 2,
            status = TranslationStatus.UNTRANSLATED,
            translatedText = ""
        )

        runBlocking {
            dao.insertAll(listOf(item1, item2, item3))

            val updatedItems = listOf(
                item1.copy(status = TranslationStatus.TRANSLATED, translatedText = "翻译1"),
                item2.copy(status = TranslationStatus.TRANSLATED, translatedText = "翻译2")
            )
            dao.batchUpdate(updatedItems)

            val all = dao.getByProjectId("p_batch_upd")
            assertEquals(3, all.size)

            val translated = all.filter { it.status == TranslationStatus.TRANSLATED }
            assertEquals(2, translated.size)
            assertTrue(translated.any { it.translatedText == "翻译1" })
            assertTrue(translated.any { it.translatedText == "翻译2" })

            val untranslated = all.filter { it.status == TranslationStatus.UNTRANSLATED }
            assertEquals(1, untranslated.size)
        }
    }
}