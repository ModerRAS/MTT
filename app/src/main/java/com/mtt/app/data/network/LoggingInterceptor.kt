package com.mtt.app.data.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Response
/**
 * Logging interceptor for OkHttp requests.
 *
 * Security features:
 * - NEVER logs request/response bodies; they may contain source text or model output
 * - REDACTs Authorization headers (API keys)
 * - REDACTs sensitive data patterns from logs
 *
 * Log levels:
 * - DEBUG builds: HEADERS level only
 * - RELEASE builds: HEADERS level only
 */
object LoggingInterceptor {

    private const val TAG = "OkHttp"

    private val SENSITIVE_PATTERNS = listOf(
        Regex("Bearer\\s+[\\w\\-.]+", RegexOption.IGNORE_CASE),
        Regex("x-api-key[:\\s]+[\\w\\-.]+", RegexOption.IGNORE_CASE),
        Regex("api[_-]?key[:\\s]+[\\w\\-.]+", RegexOption.IGNORE_CASE),
        Regex("token[:\\s]+[\\w\\-.]+", RegexOption.IGNORE_CASE),
        Regex("secret[:\\s]+[\\w\\-.]+", RegexOption.IGNORE_CASE),
        Regex("password[:\\s]+[^\\s,]+", RegexOption.IGNORE_CASE),
    )

    /**
     * Create a logging interceptor configured for DEBUG builds.
     * Logs headers only; request/response bodies may contain user text or LLM output.
     */
    fun forDebug(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            logSafe(message, Log.DEBUG)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
    }

    /**
     * Create a logging interceptor configured for RELEASE builds.
     * Only logs request/response headers, never bodies.
     */
    fun forRelease(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            logSafe(message, Log.INFO)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
    }

    /**
     * Create a custom logging interceptor with specified level.
     */
    fun create(level: HttpLoggingInterceptor.Level): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            val logLevel = if (level == HttpLoggingInterceptor.Level.BODY) Log.DEBUG else Log.INFO
            logSafe(message, logLevel)
        }.apply {
            this.level = level
        }
    }

    /**
     * Safely log a message, redacting sensitive patterns.
     */
    private fun logSafe(message: String, priority: Int) {
        val redacted = redactSensitiveData(message)
        Log.println(priority, TAG, redacted)
    }

    /**
     * Redact sensitive data from log messages.
     * NEVER logs API keys, tokens, or secrets.
     */
    private fun redactSensitiveData(message: String): String {
        var result = message

        SENSITIVE_PATTERNS.forEach { pattern ->
            result = pattern.replace(result) { matchResult ->
                val match = matchResult.value
                val separator = if (match.contains(":")) ":" else " "
                val key = match.substringBefore(separator)
                "$key: [REDACTED]"
            }
        }

        return result
    }

    /**
     * Custom interceptor for additional request/response logging.
     * Bodies are never logged, even when [logBodies] is true, because they can
     * contain source text, translated text, or other private model output.
     */
    fun createCustomInterceptor(
        logHeaders: Boolean = true,
        logBodies: Boolean = false,
        logLevel: Int = Log.DEBUG
    ): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()

            if (logHeaders) {
                Log.d(TAG, "--> ${request.method} ${request.url}")
                request.headers.forEach { (name, value) ->
                    val displayValue = if (name.equals("Authorization", ignoreCase = true) ||
                        name.equals("x-api-key", ignoreCase = true)) {
                        "[REDACTED]"
                    } else {
                        value
                    }
                    Log.d(TAG, "$name: $displayValue")
                }
            }

            val startNs = System.nanoTime()
            val response = chain.proceed(request)
            val tookMs = (System.nanoTime() - startNs) / 1_000_000

            if (logHeaders) {
                Log.d(TAG, "<-- ${response.code} ${response.message} (${tookMs}ms)")
                response.headers.forEach { (name, value) ->
                    val displayValue = if (name.equals("Set-Cookie", ignoreCase = true)) {
                        "[REDACTED]"
                    } else {
                        value
                    }
                    Log.d(TAG, "$name: $displayValue")
                }
            }

            if (logBodies) {
                Log.d(TAG, "Response body logging omitted")
            }

            response
        }
    }
}
