package com.mtt.app.data.remote.openai

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

/**
 * Unit tests for OpenAiClient.
 * Uses MockK to mock the OpenAI Java SDK.
 */
class OpenAiClientTest {

    companion object {
        private const val TEST_API_KEY = "test-api-key"
        private const val TEST_BASE_URL = "https://api.openai.com/v1"
        private const val TEST_MODEL = "gpt-4o-mini"
        private val SYSTEM_PROMPT = "Translate the following text."
        private val MESSAGES = listOf(
            LlmRequestConfig.Message(role = "user", content = "Hello, world!")
        )
    }

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var mockSdkClient: com.openai.client.OpenAIClient

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        mockSdkClient = mockk(relaxed = true)
        mockkObject(com.openai.client.okhttp.OpenAIOkHttpClient)
    }

    private fun mockSdkBuilderToReturnClient(): com.openai.client.OpenAIClient {
        val mockClient = mockSdkClient
        every {
            com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                .apiKey(TEST_API_KEY)
                .baseUrl(TEST_BASE_URL)
                .maxRetries(0)
                .build()
        } returns mockClient
        return mockClient
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — IOException → NetworkException
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when IOException thrown then throws NetworkException`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Mock the SDK client to throw IOException on any call
        every { mockClient.chat() } throws IOException("Unable to resolve host")

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected NetworkException to be thrown")
        } catch (e: NetworkException) {
            assertEquals("网络连接失败，请检查网络", e.userMessage)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — 401 Error → ApiException.authFailure()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when 401 error then throws ApiException authFailure`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 401 error with message containing "401"
        every { mockClient.chat() } throws RuntimeException(
            "Error 401: Incorrect API key provided"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key 无效，请检查设置", e.userMessage)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — 429 Error → ApiException.rateLimit()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when 429 error then throws ApiException rateLimit`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 429 error with message containing "429"
        every { mockClient.chat() } throws RuntimeException(
            "Error 429: Rate limit exceeded"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — 500/502/503/504 Error → ApiException.serverError()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when 500 error then throws ApiException serverError`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 500 error
        every { mockClient.chat() } throws RuntimeException(
            "Error 500: Internal server error"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 502 error then throws ApiException serverError`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 502 error
        every { mockClient.chat() } throws RuntimeException(
            "Error 502: Bad Gateway"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 503 error then throws ApiException serverError`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 503 error
        every { mockClient.chat() } throws RuntimeException(
            "Error 503: Service unavailable"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 504 error then throws ApiException serverError`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 504 error
        every { mockClient.chat() } throws RuntimeException(
            "Error 504: Gateway Timeout"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — 403 Error → ApiException.forbidden()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when 403 error then throws ApiException forbidden`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate 403 error
        every { mockClient.chat() } throws RuntimeException(
            "Error 403: You don't have permission to access this resource"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("权限不足", e.userMessage)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Error Mapping Tests — Unknown Error → ApiException(0, ...)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when unknown error then throws ApiException with code 0`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate an unknown error with no recognized error code
        every { mockClient.chat() } throws RuntimeException(
            "Some unexpected error occurred"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(0, e.code)
            assertTrue(e.userMessage.contains("API 请求失败"))
            assertTrue(e.userMessage.contains("Some unexpected error occurred"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge Cases: keyword matching
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `translate when unauthorized keyword then throws ApiException authFailure`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate error with "unauthorized" keyword (not a numeric code)
        every { mockClient.chat() } throws RuntimeException(
            "Request unauthorized - please check your credentials"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key 无效，请检查设置", e.userMessage)
        }
    }

    @Test
    fun `translate when rate keyword then throws ApiException rateLimit`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate error with "rate" keyword (rate limiting without numeric code)
        every { mockClient.chat() } throws RuntimeException(
            "Too many requests - rate limit exceeded"
        )

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when null message then throws ApiException with code 0`() {
        val mockClient = mockSdkBuilderToReturnClient()

        // Simulate error with null message
        every { mockClient.chat() } throws RuntimeException(null as String?)

        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(0, e.code)
        }
    }
}