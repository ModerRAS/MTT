package com.mtt.app.di

import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.local.dao.CacheItemDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing repository / manager dependencies.
 *
 * These classes sit between the data layer (DAOs) and domain layer
 * (use cases), and may require manual construction due to
 * non-injectable parameters.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * Provides a singleton [CacheManager] backed by the Room [CacheItemDao].
     * Uses the default project ID for v1 single-project mode.
     */
    @Provides
    @Singleton
    fun provideCacheManager(
        cacheItemDao: CacheItemDao
    ): CacheManager = CacheManager(cacheItemDao)
}
