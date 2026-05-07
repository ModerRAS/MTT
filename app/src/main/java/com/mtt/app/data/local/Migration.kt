package com.mtt.app.data.local

import androidx.room.migration.Migration

/**
 * Migration definitions for Room database schema changes.
 * Establishes migration patterns for future schema version upgrades.
 */
object Migration {
    /**
     * Migration from version 1 to 2.
     *
     * Adds two new tables for job tracking:
     * - translation_jobs: tracks translation execution state
     * - extraction_jobs: tracks glossary extraction state
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `translation_jobs` (
                    `job_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `total_items` INTEGER NOT NULL,
                    `completed_items` INTEGER NOT NULL,
                    `source_file_uri` TEXT NOT NULL,
                    `source_file_name` TEXT,
                    `config_json` TEXT NOT NULL,
                    `source_texts_json` TEXT,
                    `source_text_map_json` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`job_id`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `extraction_jobs` (
                    `job_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `total_chunks` INTEGER NOT NULL,
                    `completed_chunks` INTEGER NOT NULL,
                    `source_lang` TEXT NOT NULL,
                    `source_texts_json` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`job_id`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * All migrations for database construction.
     */
    val ALL_MIGRATIONS = listOf(MIGRATION_1_2)
}