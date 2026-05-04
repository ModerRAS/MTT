package com.mtt.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mtt.app.data.model.ProjectEntity

/**
 * Data Access Object for Project entities.
 * Provides CRUD operations for translation projects.
 */
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE project_id = :projectId LIMIT 1")
    suspend fun getById(projectId: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("UPDATE projects SET completed_items = :completedItems, updated_at = :updatedAt WHERE project_id = :projectId")
    suspend fun updateProgress(projectId: String, completedItems: Int, updatedAt: Long)

    @Query("DELETE FROM projects WHERE project_id = :projectId")
    suspend fun deleteById(projectId: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}