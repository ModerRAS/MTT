package com.mtt.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.ProjectDao
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.ProjectEntity

/**
 * Room database for MTT translation app.
 * Stores projects, cache items, and glossary entries.
 */
@Database(
    entities = [
        ProjectEntity::class,
        CacheItemEntity::class,
        GlossaryEntryEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun cacheItemDao(): CacheItemDao
    abstract fun glossaryDao(): GlossaryDao

    companion object {
        const val DATABASE_NAME = "mtt_database"
    }
}