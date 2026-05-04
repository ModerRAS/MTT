package com.mtt.app.data.local

import androidx.room.migration.Migration

/**
 * Migration definitions for Room database schema changes.
 * Establishes migration patterns for future schema version upgrades.
 */
object Migration {
    /**
     * Migration from version 1 to 2.
     * Currently a no-op fallback for future schema changes.
     * 
     * Add actual migration logic when schema changes are needed:
     * - ALTER TABLE statements
     * - Data migration scripts
     * - Index creation/deletion
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            // No-op for now - schema unchanged
            // Add migration logic here when upgrading from v1 to v2
        }
    }

    /**
     * All migrations for database construction.
     */
    val ALL_MIGRATIONS = listOf(MIGRATION_1_2)
}