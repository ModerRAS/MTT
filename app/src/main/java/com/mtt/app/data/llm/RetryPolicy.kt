package com.mtt.app.data.llm

import kotlinx.coroutines.delay
import okhttp3.Response
import kotlin.math.min

/**
 * Retry policy for LLM API calls with exponential backoff.
 *
 * Features:
 * - Max retries: 3
 * - Backoff: 1s → 2s → 4s → 8s → 16s → 30s cap
 * - Distinguishes retryable (429, 5xx) vs non-retryable (401, 400) errors
 */
object RetryPolicy {
    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L

    /**
     * Returns the delay in milliseconds before the next retry attempt.
     * Uses exponential backoff: 1s, 2s, 4s, capped at 30s.
     */
    fun getDelayMs(attempt: Int): Long {
        // attempt: 1 -> 1s, 2 -> 2s, 3 -> 4s, etc.
        val delay = BASE_DELAY_MS * (1 shl (attempt - 1))
        return min(delay, MAX_DELAY_MS)
    }

    /**
     * Determines whether a request should be retried based on the response.
     *
     * @param response The HTTP response from the LLM API
     * @param attempt The current attempt number (1-based)
     * @return true if the request should be retried, false otherwise
     */
    fun shouldRetry(response: Response, attempt: Int): Boolean {
        // Don't retry if we've exceeded max attempts
        if (attempt >= MAX_RETRIES) return false

        val code = response.code

        return when {
            // Non-retryable errors - don't retry
            code == 400 -> false // Bad request - the request is invalid
            code == 401 -> false // Unauthorized - credentials are invalid
            code == 403 -> false // Forbidden - access denied
            code == 404 -> false // Not found - resource doesn't exist
            isClientError(code) -> false // Other 4xx errors are not retryable

            // Retryable errors
            code == 429 -> true // Rate limited - retry with backoff
            isServerError(code) -> true // 5xx errors - server issues, retry
            isTimeoutError(code) -> true // Timeout - network/connection issue

            else -> false
        }
    }

    /**
     * Checks if the response indicates a client error (4xx except 429).
     */
    private fun isClientError(code: Int): Boolean {
        return code in 400..499 && code != 429
    }

    /**
     * Checks if the response indicates a server error (5xx).
     */
    private fun isServerError(code: Int): Boolean {
        return code in 500..599
    }

    /**
     * Checks for timeout-related status codes.
     * Note: This is a heuristic; actual timeouts may be caught as exceptions.
     */
    private fun isTimeoutError(code: Int): Boolean {
        // 408 Request Timeout
        // 504 Gateway Timeout
        return code == 408 || code == 504
    }

    /**
     * Waits for the appropriate backoff delay before the next retry.
     * This is a suspending function that should be called with kotlinx.coroutines.
     */
    suspend fun waitBeforeRetry(attempt: Int) {
        val delayMs = getDelayMs(attempt)
        delay(delayMs)
    }

    /**
     * Creates a retry summary string for logging purposes.
     */
    fun getRetrySummary(attempt: Int, response: Response): String {
        return "Retry attempt $attempt of $MAX_RETRIES for ${response.request.url}" +
                " (status: ${response.code}, retryable: ${shouldRetry(response, attempt)})"
    }
}