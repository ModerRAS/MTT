package com.mtt.app.di

import android.app.Application
import android.content.Context
import com.mtt.app.data.io.SourceTextRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Application, Context, and app-scoped dependencies.
 *
 * While Hilt auto-provides [Application] and [Context] (via [ApplicationContext]),
 * this module exists as the canonical entry point for app-scoped dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplication(
        @ApplicationContext context: Context
    ): Application = context as Application

    @Provides
    @Singleton
    fun provideSourceTextRepository(): SourceTextRepository = SourceTextRepository()
}
