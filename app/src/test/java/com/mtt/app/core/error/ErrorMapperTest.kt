package com.mtt.app.core.error

import io.mockk.every
import io.mockk.mockk
import okhttp3.Protocol
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Unit tests for ErrorMapper and MttException hierarchy.
 * Verifies Chinese error message mapping and retryability logic.
 */
class ErrorMapperTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  mapToUserMessage — MttException subclasses
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `mapToUserMessage NetworkException returns default Chinese message`() {
        val ex = NetworkException()
        assertEquals("网络连接失败，请检查网络", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage NetworkException preserves custom message`() {
        val ex = NetworkException("自定义网络错误")
        assertEquals("自定义网络错误", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ApiException 401 returns auth failure message`() {
        val ex = ApiException(401, "API Key 无效，请检查设置")
        assertEquals("API Key 无效，请检查设置", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ApiException 403 returns forbidden message`() {
        val ex = ApiException(403, "权限不足")
        assertEquals("权限不足", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ApiException 429 returns rate limit message`() {
        val ex = ApiException(429, "请求过于频繁，请稍后重试")
        assertEquals("请求过于频繁，请稍后重试", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ApiException 500 returns server error message`() {
        val ex = ApiException(500, "服务器错误，请稍后重试")
        assertEquals("服务器错误，请稍后重试", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ApiException custom message preserved`() {
        val ex = ApiException(400, "自定义 API 错误")
        assertEquals("自定义 API 错误", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ParseException returns default Chinese message`() {
        val ex = ParseException()
        assertEquals("数据解析失败", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ParseException preserves custom message`() {
        val ex = ParseException("JSON 解析错误")
        assertEquals("JSON 解析错误", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage StorageException returns default Chinese message`() {
        val ex = StorageException()
        assertEquals("存储操作失败", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage StorageException preserves custom message`() {
        val ex = StorageException("数据库写入失败")
        assertEquals("数据库写入失败", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage TranslationException returns default Chinese message`() {
        val ex = TranslationException()
        assertEquals("翻译失败", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage TranslationException preserves custom message`() {
        val ex = TranslationException("翻译超时")
        assertEquals("翻译超时", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage ValidationException preserves custom message`() {
        val ex = ValidationException("输入验证失败：内容不能为空")
        assertEquals("输入验证失败：内容不能为空", ErrorMapper.mapToUserMessage(ex))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  mapToUserMessage — IOException
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `mapToUserMessage SocketTimeoutException returns timeout message`() {
        val ex = SocketTimeoutException("Connect timed out")
        assertEquals("连接超时，请检查网络", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage generic IOException returns network failure message`() {
        val ex = IOException("Unable to resolve host")
        assertEquals("网络连接失败，请检查网络", ErrorMapper.mapToUserMessage(ex))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  mapToUserMessage — HttpException
    // ═══════════════════════════════════════════════════════════════════════

    private fun mockResponse(code: Int, message: String = "Mock Message"): Response {
        return mockk(relaxed = true) {
            every { this@mockk.code } returns code
            every { this@mockk.message } returns message
            every { this@mockk.protocol } returns Protocol.HTTP_1_1
        }
    }

    @Test
    fun `mapToUserMessage HttpException 401 returns auth failure message`() {
        val httpEx = HttpException(mockResponse(401))
        assertEquals("API Key 无效，请检查设置", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 403 returns forbidden message`() {
        val httpEx = HttpException(mockResponse(403))
        assertEquals("权限不足", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 429 returns rate limit message`() {
        val httpEx = HttpException(mockResponse(429))
        assertEquals("请求过于频繁，请稍后重试", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 500 returns server error message`() {
        val httpEx = HttpException(mockResponse(500))
        assertEquals("服务器错误，请稍后重试", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 502 returns server error message`() {
        val httpEx = HttpException(mockResponse(502))
        assertEquals("服务器错误，请稍后重试", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 503 returns server error message`() {
        val httpEx = HttpException(mockResponse(503))
        assertEquals("服务器错误，请稍后重试", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 400 returns generic API failure message`() {
        val httpEx = HttpException(mockResponse(400))
        assertEquals("API 请求失败 (400)", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 418 returns generic API failure message`() {
        val httpEx = HttpException(mockResponse(418, "I'm a teapot"))
        assertEquals("API 请求失败 (418)", ErrorMapper.mapToUserMessage(httpEx))
    }

    @Test
    fun `mapToUserMessage HttpException 404 returns generic API failure message`() {
        val httpEx = HttpException(mockResponse(404, "Not Found"))
        assertEquals("API 请求失败 (404)", ErrorMapper.mapToUserMessage(httpEx))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  mapToUserMessage — unknown Throwable
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `mapToUserMessage unknown Throwable returns generic error with message`() {
        val ex = RuntimeException("Something went wrong")
        assertEquals("未知错误: Something went wrong", ErrorMapper.mapToUserMessage(ex))
    }

    @Test
    fun `mapToUserMessage unknown Throwable with null message`() {
        val ex = object : Throwable(null as String?) {}
        assertEquals("未知错误: 未知原因", ErrorMapper.mapToUserMessage(ex))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isRetryable — MttException
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isRetryable NetworkException returns true`() {
        assertTrue(ErrorMapper.isRetryable(NetworkException()))
    }

    @Test
    fun `isRetryable ApiException 401 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(401, "auth")))
    }

    @Test
    fun `isRetryable ApiException 403 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(403, "forbidden")))
    }

    @Test
    fun `isRetryable ApiException 429 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(429, "rate limit")))
    }

    @Test
    fun `isRetryable ApiException 500 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(500, "server error")))
    }

    @Test
    fun `isRetryable ApiException 502 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(502, "bad gateway")))
    }

    @Test
    fun `isRetryable ApiException 503 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(503, "unavailable")))
    }

    @Test
    fun `isRetryable ApiException 400 returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException(400, "bad request")))
    }

    @Test
    fun `isRetryable ParseException returns false`() {
        assertFalse(ErrorMapper.isRetryable(ParseException()))
    }

    @Test
    fun `isRetryable StorageException returns false`() {
        assertFalse(ErrorMapper.isRetryable(StorageException()))
    }

    @Test
    fun `isRetryable TranslationException returns false`() {
        assertFalse(ErrorMapper.isRetryable(TranslationException()))
    }

    @Test
    fun `isRetryable ValidationException returns false`() {
        assertFalse(ErrorMapper.isRetryable(ValidationException("invalid")))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isRetryable — IOException
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isRetryable generic IOException returns true`() {
        assertTrue(ErrorMapper.isRetryable(IOException("network error")))
    }

    @Test
    fun `isRetryable SocketTimeoutException returns true`() {
        assertTrue(ErrorMapper.isRetryable(SocketTimeoutException()))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isRetryable — HttpException
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isRetryable HttpException 429 returns true`() {
        assertTrue(ErrorMapper.isRetryable(HttpException(mockResponse(429))))
    }

    @Test
    fun `isRetryable HttpException 500 returns true`() {
        assertTrue(ErrorMapper.isRetryable(HttpException(mockResponse(500))))
    }

    @Test
    fun `isRetryable HttpException 503 returns true`() {
        assertTrue(ErrorMapper.isRetryable(HttpException(mockResponse(503))))
    }

    @Test
    fun `isRetryable HttpException 401 returns false`() {
        assertFalse(ErrorMapper.isRetryable(HttpException(mockResponse(401))))
    }

    @Test
    fun `isRetryable HttpException 403 returns false`() {
        assertFalse(ErrorMapper.isRetryable(HttpException(mockResponse(403))))
    }

    @Test
    fun `isRetryable HttpException 404 returns false`() {
        assertFalse(ErrorMapper.isRetryable(HttpException(mockResponse(404))))
    }

    @Test
    fun `isRetryable HttpException 400 returns false`() {
        assertFalse(ErrorMapper.isRetryable(HttpException(mockResponse(400))))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isRetryable — unknown Throwable
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isRetryable RuntimeException returns false`() {
        assertFalse(ErrorMapper.isRetryable(RuntimeException("unexpected")))
    }

    @Test
    fun `isRetryable IllegalStateException returns false`() {
        assertFalse(ErrorMapper.isRetryable(IllegalStateException("illegal state")))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MttException factory methods
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ApiException rateLimit has correct code and message`() {
        val ex = ApiException.rateLimit()
        assertEquals(429, ex.code)
        assertEquals("请求过于频繁，请稍后重试", ex.userMessage)
    }

    @Test
    fun `ApiException authFailure has correct code and message`() {
        val ex = ApiException.authFailure()
        assertEquals(401, ex.code)
        assertEquals("API Key 无效，请检查设置", ex.userMessage)
    }

    @Test
    fun `ApiException serverError has correct code and message`() {
        val ex = ApiException.serverError()
        assertEquals(500, ex.code)
        assertEquals("服务器错误，请稍后重试", ex.userMessage)
    }

    @Test
    fun `ApiException forbidden has correct code and message`() {
        val ex = ApiException.forbidden()
        assertEquals(403, ex.code)
        assertEquals("权限不足", ex.userMessage)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Factory method retryability
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isRetryable ApiException rateLimit factory returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException.rateLimit()))
    }

    @Test
    fun `isRetryable ApiException authFailure factory returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException.authFailure()))
    }

    @Test
    fun `isRetryable ApiException serverError factory returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException.serverError()))
    }

    @Test
    fun `isRetryable ApiException forbidden factory returns false`() {
        assertFalse(ErrorMapper.isRetryable(ApiException.forbidden()))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  mapToUserMessage with factory method instances
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `mapToUserMessage ApiException rateLimit factory returns correct message`() {
        assertEquals("请求过于频繁，请稍后重试", ErrorMapper.mapToUserMessage(ApiException.rateLimit()))
    }

    @Test
    fun `mapToUserMessage ApiException authFailure factory returns correct message`() {
        assertEquals("API Key 无效，请检查设置", ErrorMapper.mapToUserMessage(ApiException.authFailure()))
    }

    @Test
    fun `mapToUserMessage ApiException serverError factory returns correct message`() {
        assertEquals("服务器错误，请稍后重试", ErrorMapper.mapToUserMessage(ApiException.serverError()))
    }

    @Test
    fun `mapToUserMessage ApiException forbidden factory returns correct message`() {
        assertEquals("权限不足", ErrorMapper.mapToUserMessage(ApiException.forbidden()))
    }
}
