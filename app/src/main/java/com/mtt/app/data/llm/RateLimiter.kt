package com.mtt.app.data.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * Token-bucket based rate limiter for LLM API calls.
 *
 * Enforces RPM (requests per minute) and TPM (tokens per minute) limits
 * using a sliding window algorithm. If no slot is available within the timeout,
 * throws [RateLimitException].
 *
 * Usage:
 * ```kotlin
 * val limiter = RateLimiter(rpmLimit = 60, tpmLimit = 60000)
 *
 * // Before making an API call:
 * limiter.acquire(tokens = estimatedTokens)
 *
 * // Make the API call...
 *
 * // After the call:
 * limiter.release(tokens = responseTokens)
 * ```
 *
 * @param rpmLimit Maximum requests per minute (default: 60)
 * @param tpmLimit Maximum tokens per minute (default: 60000)
 * @param timeoutMs Max time to wait for a slot (default: 60 seconds)
 */
class RateLimiter(
    private val rpmLimit: Int = DEFAULT_RPM,
    private val tpmLimit: Int = DEFAULT_TPM,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_RPM = 60
        const val DEFAULT_TPM = 60_000
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private val WINDOW_SIZE_MS = 60_000L // 1 minute
    }

    private val rpmSemaphore = Semaphore(rpmLimit)
    private val tpmSemaphore = Semaphore(tpmLimit)

    // Track timestamps of last rpmLimit requests (sliding window)
    private val rpmTimestamps = mutableListOf<Long>()
    private val rpmLock = Any()

    // Track timestamps of last tpmLimit tokens (sliding window)
    private val tpmTimestamps = mutableListOf<Long>()
    private val tpmLock = Any()

    /**
     * Acquire rate limit permits, waiting if necessary.
     *
     * Blocks until both RPM and TPM limits allow the request.
     * Throws [RateLimitException] if timeout is exceeded.
     *
     * @param tokens Token count for this request (for TPM tracking)
     * @throws RateLimitException if no slot available within timeout
     */
    suspend fun acquire(tokens: Int) {
        // Acquire RPM slot
        val acquiredRpm = withTimeoutOrNull(timeoutMs) {
            rpmSemaphore.withPermit {
                cleanOldTimestamps(System.currentTimeMillis())
                synchronized(rpmLock) {
                    rpmTimestamps.add(System.currentTimeMillis())
                }
                true
            }
        } ?: throw RateLimitException(
            "RPM limit reached, no slot available within ${timeoutMs}ms"
        )

        if (!acquiredRpm) {
            throw RateLimitException("RPM limit: no semaphore permit available")
        }

        // Acquire TPM slot
        val acquiredTpm = withTimeoutOrNull(timeoutMs) {
            tpmSemaphore.withPermit {
                synchronized(tpmLock) {
                    tpmTimestamps.add(System.currentTimeMillis())
                }
                true
            }
        } ?: throw RateLimitException(
            "TPM limit reached, no slot available within ${timeoutMs}ms"
        )

        if (!acquiredTpm) {
            throw RateLimitException("TPM limit: no semaphore permit available")
        }
    }

    /**
     * Check if a request with the given token count can proceed immediately
     * without waiting.
     *
     * @param tokens Token count for this request
     * @return true if both RPM and TPM limits allow immediate execution
     */
    fun canProceed(tokens: Int): Boolean {
        val now = System.currentTimeMillis()
        cleanOldTimestamps(now)

        synchronized(rpmLock) {
            if (rpmTimestamps.size >= rpmLimit) return false
        }

        synchronized(tpmLock) {
            val recentTokens = tpmTimestamps.sum()
            if (recentTokens + tokens > tpmLimit) return false
        }

        return true
    }

    /**
     * Get current rate limit status for monitoring.
     */
    fun getStatus(): RateLimitStatus {
        val now = System.currentTimeMillis()
        cleanOldTimestamps(now)

        val rpmUsed: Int
        val tpmUsed: Int
        synchronized(rpmLock) { rpmUsed = rpmTimestamps.size }
        synchronized(tpmLock) { tpmUsed = tpmTimestamps.sum().toInt() }

        return RateLimitStatus(
            rpmAvailable = rpmLimit - rpmUsed,
            rpmLimit = rpmLimit,
            tpmAvailable = tpmLimit - tpmUsed,
            tpmLimit = tpmLimit
        )
    }

    /**
     * Clean timestamps outside the sliding window.
     */
    private fun cleanOldTimestamps(now: Long) {
        val cutoff = now - WINDOW_SIZE_MS

        synchronized(rpmLock) {
            rpmTimestamps.removeAll { it < cutoff }
        }

        synchronized(tpmLock) {
            tpmTimestamps.removeAll { it < cutoff }
        }
    }
}

/**
 * Exception thrown when no rate limit slot is available within the timeout.
 */
class RateLimitException(message: String) : Exception(message)

/**
 * Current rate limit status for monitoring/UI display.
 */
data class RateLimitStatus(
    val rpmAvailable: Int,
    val rpmLimit: Int,
    val tpmAvailable: Int,
    val tpmLimit: Int
)
