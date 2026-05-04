package com.mtt.app.core.error

/**
 * Base sealed class for all MTT-specific exceptions.
 * All user-facing messages are in Chinese for UI display.
 */
sealed class MttException(val userMessage: String) : Exception(userMessage)

/**
 * Network connectivity errors
 */
class NetworkException(
    message: String = "网络连接失败，请检查网络"
) : MttException(message)

/**
 * API-related errors with HTTP status codes.
 * Common codes:
 * - 401: Authentication failure (invalid API key)
 * - 429: Rate limit exceeded
 * - 500/502/503/504: Server errors
 */
class ApiException(
    val code: Int,
    message: String
) : MttException(message) {

    companion object {
        fun rateLimit() = ApiException(429, "请求过于频繁，请稍后重试")
        fun authFailure() = ApiException(401, "API Key 无效，请检查设置")
        fun serverError() = ApiException(500, "服务器错误，请稍后重试")
        fun forbidden() = ApiException(403, "权限不足")
    }
}

/**
 * Data parsing/serialization errors
 */
class ParseException(
    message: String = "数据解析失败"
) : MttException(message)

/**
 * Local storage operation errors
 */
class StorageException(
    message: String = "存储操作失败"
) : MttException(message)

/**
 * Translation-related errors
 */
class TranslationException(
    message: String = "翻译失败"
) : MttException(message)

/**
 * Validation errors for user input
 */
class ValidationException(
    message: String
) : MttException(message)