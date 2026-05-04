package com.mtt.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.TranslationStatus

/**
 * Data Access Object for CacheItem entities.
 * Provides batch operations and status-based queries for translation cache.
 */
@Dao
interface CacheItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CacheItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CacheItemEntity>)

    @Update
    suspend fun update(item: CacheItemEntity)

    @Update
    suspend fun batchUpdate(items: List<CacheItemEntity>)

    @Query("SELECT * FROM cache_items WHERE project_id = :projectId ORDER BY text_index ASC")
    suspend fun getByProjectId(projectId: String): List<CacheItemEntity>

    @Query("SELECT * FROM cache_items WHERE project_id = :projectId AND status = :status ORDER BY text_index ASC")
    suspend fun getByStatus(projectId: String, status: TranslationStatus): List<CacheItemEntity>

    @Query("SELECT * FROM cache_items WHERE project_id = :projectId AND status IN (0, 7) ORDER BY text_index ASC")
    suspend fun getUntranslatedItems(projectId: String): List<CacheItemEntity>

    @Query("SELECT * FROM cache_items WHERE project_id = :projectId AND status IN (1, 2) ORDER BY text_index ASC")
    suspend fun getCompletedItems(projectId: String): List<CacheItemEntity>

    @Query("SELECT COUNT(*) FROM cache_items WHERE project_id = :projectId AND status = :status")
    suspend fun countByStatus(projectId: String, status: TranslationStatus): Int

    @Query("SELECT COUNT(*) FROM cache_items WHERE project_id = :projectId")
    suspend fun countTotal(projectId: String): Int

    @Query("DELETE FROM cache_items WHERE project_id = :projectId")
    suspend fun deleteByProjectId(projectId: String)

    @Query("UPDATE cache_items SET status = :status, translated_text = :translatedText, model = :model, batch_index = :batchIndex WHERE project_id = :projectId AND text_index = :textIndex")
    suspend fun updateItemStatus(
        projectId: String,
        textIndex: Int,
        status: TranslationStatus,
        translatedText: String,
        model: String,
        batchIndex: Int
    )
}