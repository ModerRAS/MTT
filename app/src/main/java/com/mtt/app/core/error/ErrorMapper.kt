package com.mtt.app.core.error

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Maps throwables to user-friendly Chinese messages.
 * Separates retryable from non-retryable errors.
 */
object ErrorMapper {

    fun mapToUserMessage(throwable: Throwable): String {
        return when (throwable) {
            is MttException -> throwable.userMessage

            is IOException -> when (throwable) {
                is SocketTimeoutException -> "连接超时，请检查网络"
                else -> "网络连接失败，请检查网络"
            }

            is HttpException -> mapHttpException(throwable)

            else -> "未知错误: ${throwable.message ?: "未知原因"}"
        }
    }

    private fun mapHttpException(e: HttpException): String {
        return when (e.code()) {
            401 -> "API Key 无效，请检查设置"
            403 -> "权限不足"
            429 -> "请求过于频繁，请稍后重试"
            in 500..599 -> "服务器错误，请稍后重试"
            else -> "API 请求失败 (${e.code()})"
        }
    }

    /**
     * Determines if an error is retryable.
     * Retryable: Network errors, 429 (rate limit), 5xx server errors
     * Non-retryable: 401/403 auth errors, 4xx client errors
     */
    fun isRetryable(throwable: Throwable): Boolean {
        return when (throwable) {
            is IOException -> true

            is HttpException -> when (throwable.code()) {
                429, in 500..599 -> true
                else -> false
            }

            is MttException -> throwable is NetworkException

            else -> false
        }
    }
}

/**
 * Wrapper for OkHttp HttpException (if using Retrofit with okHttp)
 */
class HttpException(private val response: okhttp3.Response) : Exception() {
    fun code() = response.code
    fun message() = response.message
}