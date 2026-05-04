package com.mtt.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for glossary entries.
 */
@Entity(tableName = "glossary_entries")
data class GlossaryEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "source_term")
    val sourceTerm: String,

    @ColumnInfo(name = "target_term")
    val targetTerm: String,

    @ColumnInfo(name = "match_type")
    val matchType: String = "EXACT"
) {
    companion object {
        const val MATCH_TYPE_EXACT = "EXACT"
        const val MATCH_TYPE_REGEX = "REGEX"
        const val MATCH_TYPE_CASE_INSENSITIVE = "CASE_INSENSITIVE"
    }
}