package com.mtt.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mtt.app.data.model.TranslationJobEntity
import com.mtt.app.data.model.TranslationJobSummary

/**
 * Data Access Object for [TranslationJobEntity].
 * Provides CRUD operations for translation job tracking.
 */
@Dao
interface TranslationJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: TranslationJobEntity)

    @Update
    suspend fun update(job: TranslationJobEntity)

    /**
     * Returns lightweight metadata only (no large JSON columns).
     * The sourceTextsJson and sourceTextMapJson columns can be 1-3MB combined,
     * exceeding Android's 2MB CursorWindow limit when loaded together.
     * Use [getSourceTextsJson] and [getSourceTextMapJson] separately for those.
     */
    @Query("SELECT job_id AS jobId, status, total_items AS totalItems, completed_items AS completedItems, source_file_uri AS sourceFileUri, source_file_name AS sourceFileName, config_json AS configJson, created_at AS createdAt, updated_at AS updatedAt FROM translation_jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun getMetadataById(jobId: String): TranslationJobSummary?

    @Query("SELECT source_texts_json FROM translation_jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun getSourceTextsJson(jobId: String): String?

    @Query("SELECT source_text_map_json FROM translation_jobs WHERE job_id = :jobId LIMIT 1")
    suspend fun getSourceTextMapJson(jobId: String): String?

    @Query("SELECT * FROM translation_jobs WHERE status IN (:statuses) ORDER BY updated_at DESC")
    suspend fun getByStatuses(statuses: List<String>): List<TranslationJobEntity>

    /**
     * Checks for incomplete jobs without loading large JSON columns (sourceTextsJson, sourceTextMapJson).
     * These columns can be multiple MB for large translation files and exceed Android's 2MB CursorWindow limit.
     * Call [getById] separately when the user actually clicks resume to load the full entity.
     */
    @Query("SELECT job_id AS jobId, status, total_items AS totalItems, completed_items AS completedItems, source_file_uri AS sourceFileUri, source_file_name AS sourceFileName, config_json AS configJson, created_at AS createdAt, updated_at AS updatedAt FROM translation_jobs WHERE status NOT IN (:terminalStatuses) ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestIncompleteSummary(terminalStatuses: List<String>): TranslationJobSummary?

    @Query("UPDATE translation_jobs SET status = :status, completed_items = :completedItems, updated_at = :updatedAt WHERE job_id = :jobId")
    suspend fun updateProgress(
        jobId: String,
        status: String,
        completedItems: Int,
        updatedAt: Long
    )

    @Query("DELETE FROM translation_jobs WHERE job_id = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("DELETE FROM translation_jobs WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("SELECT COUNT(*) FROM translation_jobs WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
