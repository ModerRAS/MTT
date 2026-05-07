package com.mtt.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mtt.app.data.model.ExtractionJobEntity

/**
 * Data Access Object for [ExtractionJobEntity].
 * Provides CRUD operations for glossary extraction job tracking.
 */
@Dao
interface ExtractionJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: ExtractionJobEntity)

    @Update
    suspend fun update(job: ExtractionJobEntity)

    @Query("SELECT * FROM extraction_jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun getById(jobId: String): ExtractionJobEntity?

    @Query("SELECT * FROM extraction_jobs WHERE status = :status ORDER BY updated_at DESC")
    suspend fun getByStatus(status: String): List<ExtractionJobEntity>

    @Query("SELECT * FROM extraction_jobs WHERE status NOT IN (:terminalStatuses) ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestIncomplete(terminalStatuses: List<String>): ExtractionJobEntity?

    @Query("UPDATE extraction_jobs SET status = :status, completed_chunks = :completedChunks, updated_at = :updatedAt WHERE job_id = :jobId")
    suspend fun updateProgress(
        jobId: String,
        status: String,
        completedChunks: Int,
        updatedAt: Long
    )

    @Query("DELETE FROM extraction_jobs WHERE job_id = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("DELETE FROM extraction_jobs WHERE status = :status")
    suspend fun deleteByStatus(status: String)
}
