package com.mtt.app.core.error

import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ErrorMapper and Result utilities.
 */
class ErrorMapperTest {

    // ========== ErrorMapper.mapToUserMessage tests ==========

    @Test
    fun `mapToUserMessage - NetworkException returns Chinese message`() {
        val exception = NetworkException()
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("网络连接失败，请检查网络", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 429 returns rate limit message`() {
        val exception = ApiException(429, "Too Many Requests")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("请求过于频繁，请稍后重试", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 401 returns auth failure message`() {
        val exception = ApiException(401, "Unauthorized")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("API Key 无效，请检查设置", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 403 returns forbidden message`() {
        val exception = ApiException(403, "Forbidden")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("权限不足", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 500 returns server error message`() {
        val exception = ApiException(500, "Internal Server Error")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("服务器错误，请稍后重试", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 502 returns server error message`() {
        val exception = ApiException(502, "Bad Gateway")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("服务器错误，请稍后重试", message)
    }

    @Test
    fun `mapToUserMessage - ApiException 503 returns server error message`() {
        val exception = ApiException(503, "Service Unavailable")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("服务器错误，请稍后重试", message)
    }

    @Test
    fun `mapToUserMessage - ApiException other codes returns generic message`() {
        val exception = ApiException(404, "Not Found")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("API 请求失败 (404)", message)
    }

    @Test
    fun `mapToUserMessage - IOException returns network failure message`() {
        val exception = IOException("Connection reset")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("网络连接失败，请检查网络", message)
    }

    @Test
    fun `mapToUserMessage - SocketTimeoutException returns timeout message`() {
        val exception = SocketTimeoutException("Connect timed out")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("连接超时，请检查网络", message)
    }

    @Test
    fun `mapToUserMessage - ParseException returns parsing failure message`() {
        val exception = ParseException()
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("数据解析失败", message)
    }

    @Test
    fun `mapToUserMessage - Unknown exception returns unknown error message`() {
        val exception = RuntimeException("Some random error")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertTrue(message.startsWith("未知错误:"))
    }

    @Test
    fun `mapToUserMessage - Custom MttException returns its message`() {
        val exception = TranslationException("Translation failed")
        val message = ErrorMapper.mapToUserMessage(exception)
        assertEquals("Translation failed", message)
    }

    // ========== ErrorMapper.isRetryable tests ==========

    @Test
    fun `isRetryable - NetworkException returns true`() {
        val exception = NetworkException()
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - IOException returns true`() {
        val exception = IOException("Connection failed")
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - SocketTimeoutException returns true`() {
        val exception = SocketTimeoutException()
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 429 returns true`() {
        val exception = ApiException(429, "Rate Limited")
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 500 returns true`() {
        val exception = ApiException(500, "Server Error")
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 502 returns true`() {
        val exception = ApiException(502, "Bad Gateway")
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 503 returns true`() {
        val exception = ApiException(503, "Service Unavailable")
        assertTrue(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 401 returns false`() {
        val exception = ApiException(401, "Unauthorized")
        assertFalse(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 403 returns false`() {
        val exception = ApiException(403, "Forbidden")
        assertFalse(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - ApiException 404 returns false`() {
        val exception = ApiException(404, "Not Found")
        assertFalse(ErrorMapper.isRetryable(exception))
    }

    @Test
    fun `isRetryable - Unknown exception returns false`() {
        val exception = RuntimeException("Unknown")
        assertFalse(ErrorMapper.isRetryable(exception))
    }

    // ========== Result tests ==========

    @Test
    fun `Result - success onSuccess called`() {
        var resultData: String? = null
        val result = Result.success("test data")
        result.onSuccess { resultData = it }
        assertEquals("test data", resultData)
    }

    @Test
    fun `Result - failure onFailure called`() {
        var resultException: MttException? = null
        val exception = NetworkException()
        val result: Result<String> = Result.failure(exception)
        result.onFailure { resultException = it }
        assertEquals(exception, resultException)
    }

    @Test
    fun `Result - map transforms success`() {
        val result = Result.success(10)
        val mapped = result.map { it * 2 }
        assertEquals(20, (mapped as Result.Success).data)
    }

    @Test
    fun `Result - map preserves failure`() {
        val exception = NetworkException()
        val result: Result<Int> = Result.failure(exception)
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Failure)
        assertEquals(exception, (mapped as Result.Failure).exception)
    }

    @Test
    fun `Result - flatMap chains success`() {
        val result = Result.success(10)
        val chained = result.flatMap { Result.success(it * 2) }
        assertEquals(20, (chained as Result.Success).data)
    }

    @Test
    fun `Result - flatMap propagates failure`() {
        val exception = NetworkException()
        val result: Result<Int> = Result.failure(exception)
        val chained = result.flatMap { Result.success(it * 2) }
        assertTrue(chained is Result.Failure)
    }

    @Test
    fun `Result - getOrNull returns data on success`() {
        val result = Result.success("test")
        assertEquals("test", result.getOrNull())
    }

    @Test
    fun `Result - getOrNull returns null on failure`() {
        val result: Result<String> = Result.failure(NetworkException())
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `Result - getOrThrow returns data on success`() {
        val result = Result.success("test")
        assertEquals("test", result.getOrThrow())
    }

    @Test
    fun `Result - getOrThrow throws on failure`() {
        val exception = NetworkException()
        val result: Result<String> = Result.failure(exception)
        try {
            result.getOrThrow()
            assert(false) { "Should have thrown" }
        } catch (e: MttException) {
            assertEquals(exception, e)
        }
    }

    @Test
    fun `Result - getOrDefault returns data on success`() {
        val result = Result.success("test")
        assertEquals("test", result.getOrDefault("default"))
    }

    @Test
    fun `Result - getOrDefault returns default on failure`() {
        val result: Result<String> = Result.failure(NetworkException())
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `Result - isSuccess returns true for success`() {
        val result = Result.success("test")
        assertTrue(result.isSuccess())
        assertFalse(result.isFailure())
    }

    @Test
    fun `Result - isFailure returns true for failure`() {
        val result: Result<String> = Result.failure(NetworkException())
        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
    }
}