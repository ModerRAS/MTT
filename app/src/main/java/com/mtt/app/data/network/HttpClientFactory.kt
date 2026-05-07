package com.mtt.app.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Factory for creating configured OkHttpClient instances.
 *
 * Provides specialized clients for:
 * - OpenAI API: Uses "Authorization: Bearer" header
 * - Anthropic API: Uses "x-api-key" header and "anthropic-version" header
 *
 * All clients include:
 * - RetryInterceptor with exponential backoff
 * - LoggingInterceptor (debug/release appropriate)
 * - Configurable timeouts
 */
object HttpClientFactory {

    // Timeout configuration
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 300L
    const val WRITE_TIMEOUT_SECONDS = 30L

    // Retry configuration
    const val MAX_RETRIES = 3

    /**
     * Create OkHttpClient configured for OpenAI API.
     *
     * @param apiKey OpenAI API key for authentication
     * @param baseUrl Base URL for the API (e.g., "https://api.openai.com/v1")
     * @param debugMode Whether to enable verbose logging (true for debug builds)
     * @return Configured OkHttpClient instance
     */
    fun createOpenAiClient(
        apiKey: String,
        baseUrl: String,
        debugMode: Boolean = false
    ): OkHttpClient {
        return createBaseClient(debugMode)
            .addInterceptor(AuthInterceptor.forOpenAi(apiKey))
            .build()
    }

    /**
     * Create OkHttpClient configured for Anthropic API.
     *
     * @param apiKey Anthropic API key for authentication
     * @param baseUrl Base URL for the API (e.g., "https://api.anthropic.com")
     * @param debugMode Whether to enable verbose logging (true for debug builds)
     * @return Configured OkHttpClient instance
     */
    fun createAnthropicClient(
        apiKey: String,
        baseUrl: String,
        debugMode: Boolean = false
    ): OkHttpClient {
        return createBaseClient(debugMode)
            .addInterceptor(AuthInterceptor.forAnthropic(apiKey))
            .build()
    }

    /**
     * Create a base OkHttpClient with shared configuration.
     * Includes retry interceptor and logging, but no auth.
     */
    fun createBaseClient(debugMode: Boolean = false): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = MAX_RETRIES))
            .apply {
                if (debugMode) {
                    addInterceptor(LoggingInterceptor.forDebug())
                } else {
                    addInterceptor(LoggingInterceptor.forRelease())
                }
            }
    }

    /**
     * Create a builder with custom timeout configuration.
     */
    fun createBuilder(
        connectTimeout: Long = CONNECT_TIMEOUT_SECONDS,
        readTimeout: Long = READ_TIMEOUT_SECONDS,
        writeTimeout: Long = WRITE_TIMEOUT_SECONDS
    ): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
    }
}

/**
 * Interceptor that adds authentication headers to requests.
 */
class AuthInterceptor private constructor(
    private val authHeader: Pair<String, String>
) : Interceptor {

    companion object {
        /**
         * Create auth interceptor for OpenAI API.
         * Adds: Authorization: Bearer {apiKey}
         */
        fun forOpenAi(apiKey: String): AuthInterceptor {
            return AuthInterceptor("Authorization" to "Bearer $apiKey")
        }

        /**
         * Create auth interceptor for Anthropic API.
         * Adds: x-api-key: {apiKey} and anthropic-version: 2023-06-01
         */
        fun forAnthropic(apiKey: String): Interceptor {
            return AnthropicAuthInterceptor(apiKey)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val newRequest = originalRequest.newBuilder()
            .header(authHeader.first, authHeader.second)
            .build()
        return chain.proceed(newRequest)
    }
}

/**
 * Special auth interceptor for Anthropic API that adds multiple headers.
 */
class AnthropicAuthInterceptor(
    private val apiKey: String
) : Interceptor {

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val newRequest = originalRequest.newBuilder()
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .build()
        return chain.proceed(newRequest)
    }
}
