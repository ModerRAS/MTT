package com.mtt.app.di

import android.content.Context
import com.mtt.app.data.security.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for security-related dependencies.
 *
 * Uses Application context (not Activity context) to survive process death.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    /**
     * Provides a singleton SecureStorage instance.
     *
     * Using Application context ensures the encrypted preferences survive
     * process death and Activity recreation.
     */
    @Provides
    @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context
    ): SecureStorage {
        return SecureStorage(context)
    }
}