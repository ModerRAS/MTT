package com.mtt.app.di

import android.content.Context
import androidx.room.Room
import com.mtt.app.data.local.AppDatabase
import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAO instances.
 * Installed in SingletonComponent for single database instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(Migration.ALL_MIGRATIONS)
            .build()
    }

    @Provides
    @Singleton
    fun provideProjectDao(database: AppDatabase): ProjectDao {
        return database.projectDao()
    }

    @Provides
    @Singleton
    fun provideCacheItemDao(database: AppDatabase): CacheItemDao {
        return database.cacheItemDao()
    }

    @Provides
    @Singleton
    fun provideGlossaryDao(database: AppDatabase): GlossaryDao {
        return database.glossaryDao()
    }
}