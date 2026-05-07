package com.mtt.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for tracking glossary extraction job state.
 *
 * Persisted to enable resumability after process death.
 * Glossary extraction is a 2-phase process:
 * 1. Frequency analysis (local, fast)
 * 2. LLM validation (N chunks of candidates, slow)
 *
 * On resume, completed chunks are skipped and only pending ones are re-validated.
 */
@Entity(tableName = "extraction_jobs")
data class ExtractionJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    @ColumnInfo(name = "completed_chunks")
    val completedChunks: Int,

    @ColumnInfo(name = "source_lang")
    val sourceLang: String,

    /** JSON-serialized source text map, persisted to survive process death. */
    @ColumnInfo(name = "source_texts_json")
    val sourceTextsJson: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    companion object {
        const val STATUS_FREQUENCY_ANALYSIS = "FREQUENCY_ANALYSIS"
        const val STATUS_LLM_VALIDATION = "LLM_VALIDATION"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"

        /**
         * Serialize a text map to a JSON string for persistence.
         */
        fun serializeTexts(texts: Map<String, String>): String {
            val obj = org.json.JSONObject()
            texts.forEach { (k, v) -> obj.put(k, v) }
            return obj.toString()
        }

        /**
         * Deserialize a JSON string back to a text map.
         */
        fun deserializeTexts(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            try {
                val obj = org.json.JSONObject(json)
                val map = LinkedHashMap<String, String>()
                obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
                return map
            } catch (_: Exception) {
                return emptyMap()
            }
        }
    }
}
