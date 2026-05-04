package com.mtt.app.di

import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.domain.pipeline.TranslationExecutor
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for use-case and utility dependencies.
 *
 * Both [TranslationExecutor] and [TranslateTextsUseCase] are constructor-injected
 * (annotated with [javax.inject.Inject]) and are auto-wired by Hilt.
 *
 * [RateLimiter] is provided manually here because its constructor has all-default
 * parameters, which confuses Hilt's @Inject detection (it sees both a parameterized
 * and a no-arg constructor).
 */
@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideRateLimiter(): RateLimiter = RateLimiter()
}
