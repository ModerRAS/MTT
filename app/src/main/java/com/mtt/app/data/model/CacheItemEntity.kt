package com.mtt.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cache items storing translation results.
 */
@Entity(
    tableName = "cache_items",
    primaryKeys = ["project_id", "text_index"]
)
data class CacheItemEntity(
    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "text_index")
    val textIndex: Int,

    @ColumnInfo(name = "status")
    val status: TranslationStatus,

    @ColumnInfo(name = "source_text")
    val sourceText: String,

    @ColumnInfo(name = "translated_text")
    val translatedText: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "batch_index")
    val batchIndex: Int
)