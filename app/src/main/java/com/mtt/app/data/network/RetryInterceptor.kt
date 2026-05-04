package com.mtt.app.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Interceptor that implements retry logic with exponential backoff for specific HTTP status codes.
 *
 * Features:
 * - Retries on 429 (Too Many Requests), 500, 502, 503, 504 errors
 * - Exponential backoff: 1s → 2s → 4s → 8s → 16s → max 30s
 * - Reads Retry-After header on 429 responses
 * - Throws [RetryException] when max retries exceeded
 */
class RetryInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val multiplier: Double = DEFAULT_MULTIPLIER
) : Interceptor {

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        const val DEFAULT_MULTIPLIER = 2.0

        /** HTTP status codes that trigger a retry */
        val RETRYABLE_CODES = setOf(
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var exception: IOException? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                response?.close() // Close previous response before new attempt
                response = chain.proceed(request)

                if (isRetryable(response.code)) {
                    attempt++
                    if (attempt > maxRetries) {
                        throw RetryException(
                            "Max retries ($maxRetries) exceeded for ${request.url}",
                            response
                        )
                    }

                    val delayMs = calculateDelay(response, attempt)
                    Thread.sleep(delayMs)
                    continue
                }

                return response

            } catch (e: IOException) {
                exception = e
                attempt++
                if (attempt > maxRetries) {
                    throw RetryException(
                        "Max retries ($maxRetries) exceeded for ${request.url}",
                        response,
                        e
                    )
                }

                val delayMs = calculateBackoffDelay(attempt)
                Thread.sleep(delayMs)
            }
        }

        // This should never be reached, but return last response if available
        return response ?: throw exception ?: RetryException(
            "Unexpected error in retry logic for ${request.url}",
            null
        )
    }

    /**
     * Check if the HTTP status code is retryable.
     */
    private fun isRetryable(statusCode: Int): Boolean = statusCode in RETRYABLE_CODES

    /**
     * Calculate delay before next retry attempt.
     * For 429 responses, prioritizes Retry-After header if present.
     */
    private fun calculateDelay(response: Response, attempt: Int): Long {
        return when (response.code) {
            429 -> {
                // Try to read Retry-After header first
                val retryAfter = response.header("Retry-After")
                when {
                    retryAfter != null -> parseRetryAfterHeader(retryAfter)
                    else -> calculateBackoffDelay(attempt)
                }
            }
            in RETRYABLE_CODES -> calculateBackoffDelay(attempt)
            else -> 0L
        }
    }

    /**
     * Parse Retry-After header value.
     * Supports both seconds format and HTTP-date format (RFC 7231).
     */
    private fun parseRetryAfterHeader(value: String): Long {
        return try {
            // Try parsing as seconds first
            val seconds = value.toLongOrNull()
            if (seconds != null) {
                TimeUnit.SECONDS.toMillis(seconds)
            } else {
                // Try parsing as HTTP-date (e.g., "Wed, 21 Oct 2015 07:28:00 GMT")
                val retryDate = java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z",
                    java.util.Locale.US
                ).parse(value)
                if (retryDate != null) {
                    val now = System.currentTimeMillis()
                    val retryTime = retryDate.time
                    maxOf(0L, retryTime - now)
                } else {
                    calculateBackoffDelay(1)
                }
            }
        } catch (e: Exception) {
            // Fallback to exponential backoff on any parsing error
            calculateBackoffDelay(1)
        }
    }

    /**
     * Calculate exponential backoff delay with jitter.
     * Formula: min(initialDelay * multiplier^attempt, maxDelay)
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = (initialDelayMs * Math.pow(multiplier, attempt.toDouble())).toLong()
        return min(delay, maxDelayMs)
    }
}

/**
 * Exception thrown when retry logic exhausts all attempts.
 */
class RetryException(
    message: String,
    val response: Response?,
    cause: Throwable? = null
) : IOException(message, cause)
