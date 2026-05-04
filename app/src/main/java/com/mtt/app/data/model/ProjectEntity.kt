package com.mtt.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for translation projects.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "source_lang")
    val sourceLang: String,

    @ColumnInfo(name = "target_lang")
    val targetLang: String,

    @ColumnInfo(name = "source_file_uri")
    val sourceFileUri: String,

    @ColumnInfo(name = "total_items")
    val totalItems: Int,

    @ColumnInfo(name = "completed_items")
    val completedItems: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)