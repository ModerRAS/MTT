package com.mtt.app.data.remote.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.Usage
import com.anthropic.services.blocking.MessageService
import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmRequestConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Optional

class AnthropicClientTest {

    companion object {
        private const val TEST_API_KEY = "test-anthropic-key"
        private const val TEST_BASE_URL = "https://api.anthropic.com"
        private const val TEST_MODEL = "claude-3-5-sonnet-20241022"
        private const val TEST_SYSTEM_PROMPT = "You are a translator."
        private val TEST_MESSAGES = listOf(
            LlmRequestConfig.Message(role = "user", content = "Hello world")
        )
    }

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var mockSdkClient: AnthropicClient

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        mockSdkClient = mockk(relaxed = true)
        mockkObject(com.anthropic.client.okhttp.AnthropicOkHttpClient)
    }

    private fun mockSdkBuilderToReturnClient(): AnthropicClient {
        val mockClient = mockSdkClient
        val mockBuilder = mockk<com.anthropic.client.okhttp.AnthropicOkHttpClient.Builder>()
        every { mockBuilder.apiKey(TEST_API_KEY) } returns mockBuilder
        every { mockBuilder.baseUrl(TEST_BASE_URL) } returns mockBuilder
        every { mockBuilder.maxRetries(0) } returns mockBuilder
        every { mockBuilder.timeout(any<java.time.Duration>()) } returns mockBuilder
        every { mockBuilder.putHeader("anthropic-version", "2023-06-01") } returns mockBuilder
        every { mockBuilder.build() } returns mockClient

        every {
            com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
        } returns mockBuilder
        return mockClient
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Successful translation test
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun translateSuccessfulReturnsTranslationResponse() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockResponse = mockk<Message>()

        val mockTextBlock = mockk<com.anthropic.models.messages.TextBlock>()
        every { mockTextBlock.text() } returns "你好世界"

        val mockContentBlock = mockk<ContentBlock>()
        every { mockContentBlock.text() } returns Optional.of(mockTextBlock)
        every { mockResponse.content() } returns listOf(mockContentBlock)

        val mockUsage = mockk<Usage>()
        every { mockUsage.inputTokens() } returns 10L
        every { mockUsage.outputTokens() } returns 20L
        every { mockResponse.usage() } returns mockUsage

        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } returns mockResponse

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        val result = client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)

        assertEquals("你好世界", result.content)
        assertEquals(TEST_MODEL, result.model)
        assertEquals(30, result.tokensUsed)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun translateWhenIOExceptionThrownThenThrowsNetworkException() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws IOException("Unable to resolve host")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected NetworkException")
        } catch (e: NetworkException) {
            assertTrue(e.userMessage.startsWith("网络连接失败，请检查网络"))
        }
    }

    @Test
    fun translateWhen401ErrorThenThrowsApiExceptionAuthFailure() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 401 unauthorized")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key 无效，请检查设置", e.userMessage)
        }
    }

    @Test
    fun translateWhen429ErrorThenThrowsApiExceptionRateLimit() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 429 rate limit exceeded")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun translateWhen500ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 500 internal server error")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun translateWhen502ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 502 bad gateway")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun translateWhen503ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 503 service unavailable")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun translateWhen504ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 504 gateway timeout")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun translateWhen403ErrorThenThrowsApiExceptionForbidden() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("anthropic_error status code: 403 forbidden")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("权限不足", e.userMessage)
        }
    }

    @Test
    fun translateWhenUnknownErrorThenThrowsApiExceptionWithCode0() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("Some unexpected error")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(0, e.code)
            assertTrue(e.userMessage.contains("API 请求失败"))
        }
    }

    @Test
    fun translateWhenUnauthorizedKeywordThenThrowsApiExceptionAuthFailure() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("Your request is unauthorized")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key 无效，请检查设置", e.userMessage)
        }
    }

    @Test
    fun translateWhenRateKeywordThenThrowsApiExceptionRateLimit() {
        val mockClient = mockSdkBuilderToReturnClient()
        val mockMessages: MessageService = mockk(relaxed = true)
        every { mockClient.messages() } returns mockMessages
        every { mockMessages.create(any()) } throws RuntimeException("Too many requests - rate limit exceeded")

        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }
}
