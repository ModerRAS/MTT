package com.mtt.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.ProjectDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.ExtractionJobEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.ProjectEntity
import com.mtt.app.data.model.TranslationJobEntity

/**
 * Room database for MTT translation app.
 * Stores projects, cache items, and glossary entries.
 */
@Database(
    entities = [
        ProjectEntity::class,
        CacheItemEntity::class,
        GlossaryEntryEntity::class,
        TranslationJobEntity::class,
        ExtractionJobEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun cacheItemDao(): CacheItemDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun translationJobDao(): TranslationJobDao
    abstract fun extractionJobDao(): ExtractionJobDao

    companion object {
        const val DATABASE_NAME = "mtt_database"
    }
}