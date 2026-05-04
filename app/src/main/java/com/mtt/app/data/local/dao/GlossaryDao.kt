package com.mtt.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mtt.app.data.model.GlossaryEntryEntity

/**
 * Data Access Object for GlossaryEntry entities.
 * Provides operations for managing translation glossary terms.
 */
@Dao
interface GlossaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: GlossaryEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<GlossaryEntryEntity>)

    @Query("SELECT * FROM glossary_entries WHERE project_id = :projectId ORDER BY source_term ASC")
    suspend fun getByProjectId(projectId: String): List<GlossaryEntryEntity>

    @Query("DELETE FROM glossary_entries WHERE project_id = :projectId")
    suspend fun deleteByProjectId(projectId: String)

    @Query("SELECT COUNT(*) FROM glossary_entries WHERE project_id = :projectId")
    suspend fun countByProjectId(projectId: String): Int

    @Query("DELETE FROM glossary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}