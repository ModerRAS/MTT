package com.mtt.app.data.remote.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.Usage
import com.anthropic.models.messages.TextBlock
import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmRequestConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Duration
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

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
    }

    private fun mockAnthropicSdkClient(): AnthropicClient {
        val mockClient = mockk<AnthropicClient>()
        mockkStatic("com.anthropic.client.okhttp.AnthropicOkHttpClientKt")
        val mockBuilder = mockk<com.anthropic.client.okhttp.AnthropicOkHttpClient.Builder> {
            every { apiKey(TEST_API_KEY) } returns this
            every { baseUrl(TEST_BASE_URL) } returns this
            every { maxRetries(0) } returns this
            every { timeout(any<Duration>()) } returns this
            every { putHeader(any(), any()) } returns this
            every { build() } returns mockClient
        }
        every { com.anthropic.client.okhttp.AnthropicOkHttpClient.builder() } returns mockBuilder
        return mockClient
    }

    @Test
    fun translateSuccessfulReturnsTranslationResponse() {
        val mockClient = mockAnthropicSdkClient()
@Suppress("UNCHECKED_CAST")
        val mockTextBlock = mockk<ContentBlock>(relaxed = true) {
            every { text() } returns Optional.of(
                mockk<TextBlock>(relaxed = true) {
                    every { text() } returns Optional.of("你好世界")
                }
            )
        }
        val mockResponse = mockk<Message> {
            every { content() } returns listOf(mockTextBlock)
            every { usage() } returns mockk<Usage> {
                every { inputTokens() } returns 10L
                every { outputTokens() } returns 20L
            }
        }
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } returns mockResponse
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        val result = client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
        assertEquals("�������", result.content)
        assertEquals(TEST_MODEL, result.model)
        assertEquals(30, result.tokensUsed)
    }

    @Test
    fun translateSuccessfulReturnsTranslationResponse() {
        val mockClient = mockAnthropicSdkClient()
        val mockResponse = mockk<Message>(relaxed = true)
        every { mockResponse.content() } returns listOf(
            mockk<ContentBlock>(relaxed = true) {
                every { text() } returns Optional.of(
                    mockk<com.anthropic.models.messages.TextBlock>(relaxed = true) {
                        every { text() } returns Optional.of("你好世界")
                    }
                )
            }
        )
        every { mockResponse.usage() } returns mockk(relaxed = true) {
            every { inputTokens() } returns 10L
            every { outputTokens() } returns 20L
        }
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } returns mockResponse
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        val result = client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
        assertEquals("你好世界", result.content)
        assertEquals(TEST_MODEL, result.model)
        assertEquals(30, result.tokensUsed)
    }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected NetworkException to be thrown")
        } catch (e: NetworkException) {
            assertEquals("��������ʧ�ܣ���������", e.userMessage)
        }
    }

    @Test
    fun translateWhen401ErrorThenThrowsApiExceptionAuthFailure() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 401 unauthorized")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key ��Ч����������", e.userMessage)
        }
    }

    @Test
    fun translateWhen429ErrorThenThrowsApiExceptionRateLimit() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 429 rate limit exceeded")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("�������Ƶ�������Ժ�����", e.userMessage)
        }
    }

    @Test
    fun translateWhen500ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 500 internal server error")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("�������������Ժ�����", e.userMessage)
        }
    }

    @Test
    fun translateWhen502ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 502 bad gateway")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("�������������Ժ�����", e.userMessage)
        }
    }

    @Test
    fun translateWhen503ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 503 service unavailable")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("�������������Ժ�����", e.userMessage)
        }
    }

    @Test
    fun translateWhen504ErrorThenThrowsApiExceptionServerError() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 504 gateway timeout")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("�������������Ժ�����", e.userMessage)
        }
    }

    @Test
    fun translateWhen403ErrorThenThrowsApiExceptionForbidden() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("nthropic_error status code: 403 forbidden")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("Ȩ�޲���", e.userMessage)
        }
    }

    @Test
    fun translateWhenUnknownErrorThenThrowsApiExceptionWithCode0() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("Some unexpected error occurred")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(0, e.code)
            assertTrue(e.userMessage.contains("API ����ʧ��"))
            assertTrue(e.userMessage.contains("Some unexpected error occurred"))
        }
    }

    @Test
    fun translateWhenUnauthorizedKeywordThenThrowsApiExceptionAuthFailure() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("Your request is unauthorized - please check your credentials")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key ��Ч����������", e.userMessage)
        }
    }

    @Test
    fun translateWhenRateKeywordThenThrowsApiExceptionRateLimit() {
        val mockClient = mockAnthropicSdkClient()
        every { mockClient.messages() } returns mockk(relaxed = true) {
            every { create(any()) } throws RuntimeException("Too many requests - rate limit exceeded")
        }
        val client = AnthropicClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
        try {
            client.translate(TEST_MESSAGES, TEST_SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("�������Ƶ�������Ժ�����", e.userMessage)
        }
    }
}
