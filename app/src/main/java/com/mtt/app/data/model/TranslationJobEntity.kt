package com.mtt.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight projection of [TranslationJobEntity] without the large JSON columns.
 * Used for the initial resume detection query to avoid CursorWindow overflow
 * (single row with multi-MB sourceTextsJson exceeds Android's 2MB CursorWindow limit).
 */
data class TranslationJobSummary(
    val jobId: String,
    val status: String,
    val totalItems: Int,
    val completedItems: Int,
    val sourceFileUri: String,
    val sourceFileName: String?,
    val configJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Room entity for tracking translation job state.
 *
 * Persisted to enable resumability after process death.
 * API keys and glossary entries are NOT persisted — they are
 * reloaded from [com.mtt.app.data.security.SecureStorage] and
 * [com.mtt.app.data.local.dao.GlossaryDao] on resume.
 */
@Entity(tableName = "translation_jobs")
data class TranslationJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "total_items")
    val totalItems: Int,

    @ColumnInfo(name = "completed_items")
    val completedItems: Int,

    @ColumnInfo(name = "source_file_uri")
    val sourceFileUri: String,

    @ColumnInfo(name = "source_file_name")
    val sourceFileName: String?,

    /**
     * JSON-serialized resume config containing mode, model info (without API key),
     * source/target language, temperature, maxTokens, batchSize, concurrency.
     */
    @ColumnInfo(name = "config_json")
    val configJson: String,

    /**
     * JSON-serialized array of source texts (the values from the MTool JSON).
     * Persisted so the job can be resumed without re-reading from [sourceFileUri],
     * which may lose read permissions after process death.
     */
    @ColumnInfo(name = "source_texts_json")
    val sourceTextsJson: String?,

    /**
     * JSON-serialized object of the original key-value pairs from the MTool JSON.
     * Required for correct export after resume.
     */
    @ColumnInfo(name = "source_text_map_json")
    val sourceTextMapJson: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        /**
         * Serialize a list of source texts to a JSON array string.
         */
        fun serializeTexts(texts: List<String>): String {
            return JSONArray(texts).toString()
        }

        /**
         * Deserialize a JSON array string back to a list of source texts.
         */
        fun deserializeTexts(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.optString(it, "") }
        }

        /**
         * Serialize a key-value map to a JSON object string.
         */
        fun serializeTextMap(map: Map<String, String>): String {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            return obj.toString()
        }

        /**
         * Deserialize a JSON object string back to a key-value map.
         */
        fun deserializeTextMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            obj.keys().forEach { key ->
                map[key] = obj.optString(key, "")
            }
            return map
        }

        /**
         * Serialize a subset of [TranslationConfig] to JSON for persistence.
         * Excludes API keys and glossary entries (reloaded on resume).
         */
        fun serializeConfig(config: TranslationConfig): String {
            val model = config.model
            val providerType = when (model.provider) {
                is LlmProvider.OpenAI -> "openai"
                is LlmProvider.Anthropic -> "anthropic"
            }
            val baseUrl = when (model.provider) {
                is LlmProvider.OpenAI -> model.provider.baseUrl
                is LlmProvider.Anthropic -> model.provider.baseUrl
            }

            return JSONObject().apply {
                put("mode", config.mode.name)
                put("modelId", model.modelId)
                put("modelDisplayName", model.displayName)
                put("modelContextWindow", model.contextWindow)
                put("modelProvider", providerType)
                put("modelBaseUrl", baseUrl)
                put("modelIsCustom", model.isCustom)
                put("sourceLang", config.sourceLang)
                put("targetLang", config.targetLang)
                put("temperature", config.temperature.toDouble())
                put("maxTokens", config.maxTokens)
                put("batchSize", config.batchSize)
                put("concurrency", config.concurrency)
            }.toString()
        }

        /**
         * Deserialize config JSON back to [TranslationConfig].
         * API key and baseUrl are passed explicitly since they are not stored.
         * Glossary entries are loaded separately via GlossaryDao.
         */
        fun deserializeConfig(
            json: String,
            apiKey: String,
            baseUrlOverride: String?,
            glossaryEntries: List<GlossaryEntryEntity>
        ): TranslationConfig {
            val obj = JSONObject(json)
            val mode = TranslationMode.valueOf(obj.getString("mode"))
            val modelId = obj.getString("modelId")
            val displayName = obj.optString("modelDisplayName", modelId)
            val contextWindow = obj.optInt("modelContextWindow", 128000)
            val providerType = obj.optString("modelProvider", "openai")
            val modelBaseUrl = baseUrlOverride ?: obj.optString("modelBaseUrl", "")
            val isCustom = obj.optBoolean("modelIsCustom", false)

            val provider = when (providerType) {
                "anthropic" -> LlmProvider.Anthropic(apiKey, modelBaseUrl)
                else -> LlmProvider.OpenAI(apiKey, modelBaseUrl)
            }

            val model = ModelInfo(
                modelId = modelId,
                displayName = displayName,
                contextWindow = contextWindow,
                provider = provider,
                isCustom = isCustom
            )

            return TranslationConfig(
                mode = mode,
                model = model,
                sourceLang = obj.getString("sourceLang"),
                targetLang = obj.getString("targetLang"),
                glossaryEntries = glossaryEntries,
                temperature = obj.optDouble("temperature", 0.3).toFloat(),
                maxTokens = obj.optInt("maxTokens", 4096),
                batchSize = obj.optInt("batchSize", 50),
                concurrency = obj.optInt("concurrency", 1)
            )
        }
    }
}
