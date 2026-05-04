package com.mtt.app.di

import com.mtt.app.BuildConfig
import com.mtt.app.data.network.HttpClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt module providing network-related dependencies.
 *
 * Provides:
 * - Base OkHttpClient (without API key authentication)
 * - HttpClientFactory for creating authenticated clients
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provides a base OkHttpClient with shared configuration.
     * This client does NOT include authentication - use HttpClientFactory
     * for creating authenticated clients with API keys.
     *
     * The client includes:
     * - RetryInterceptor with exponential backoff
     * - LoggingInterceptor (debug/release appropriate)
     * - Default timeouts (15s connect, 60s read, 30s write)
     *
     * @return Configured OkHttpClient instance
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return HttpClientFactory.createBaseClient(debugMode = BuildConfig.DEBUG).build()
    }

    /**
     * Provides HttpClientFactory as a singleton injectable object.
     * Use this to create authenticated clients with API keys:
     *
     * ```kotlin
     * val client = httpClientFactory.createOpenAiClient(apiKey, baseUrl)
     * ```
     */
    @Provides
    @Singleton
    fun provideHttpClientFactory(): HttpClientFactory {
        return HttpClientFactory
    }
}
