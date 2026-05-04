package com.mtt.app.data.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Unit tests for HttpClientFactory and related interceptors.
 */
class HttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ===== createOpenAiClient Tests =====

    @Test
    fun `createOpenAiClient sends Authorization Bearer header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createOpenAiClient(
            apiKey = "test-api-key",
            baseUrl = server.url("/").toString()
        )

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        client.newCall(request).execute()

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer test-api-key", recordedRequest.getHeader("Authorization"))
    }

    // ===== createAnthropicClient Tests =====

    @Test
    fun `createAnthropicClient sends x-api-key header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createAnthropicClient(
            apiKey = "anthropic-key-123",
            baseUrl = server.url("/").toString()
        )

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        client.newCall(request).execute()

        val recordedRequest = server.takeRequest()
        assertEquals("anthropic-key-123", recordedRequest.getHeader("x-api-key"))
    }

    @Test
    fun `createAnthropicClient sends anthropic-version header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createAnthropicClient(
            apiKey = "anthropic-key-123",
            baseUrl = server.url("/").toString()
        )

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        client.newCall(request).execute()

        val recordedRequest = server.takeRequest()
        assertEquals("2023-06-01", recordedRequest.getHeader("anthropic-version"))
    }

    // ===== Timeout Configuration Tests =====

    @Test
    fun `createBaseClient has correct default timeouts`() {
        val builder = HttpClientFactory.createBaseClient()

        // Use reflection to check private timeout values via OkHttpClient internals
        // Instead, verify via actual connection behavior
        val client = builder.build()
        assertEquals(HttpClientFactory.CONNECT_TIMEOUT_SECONDS,
            client.connectTimeoutMillis / 1000)
        assertEquals(HttpClientFactory.READ_TIMEOUT_SECONDS,
            client.readTimeoutMillis / 1000)
        assertEquals(HttpClientFactory.WRITE_TIMEOUT_SECONDS,
            client.writeTimeoutMillis / 1000)
    }

    @Test
    fun `createBuilder with custom timeouts sets correct values`() {
        val builder = HttpClientFactory.createBuilder(
            connectTimeout = 5L,
            readTimeout = 120L,
            writeTimeout = 10L
        )
        val client = builder.build()

        assertEquals(5L, client.connectTimeoutMillis / 1000)
        assertEquals(120L, client.readTimeoutMillis / 1000)
        assertEquals(10L, client.writeTimeoutMillis / 1000)
    }

    // ===== RetryInterceptor Tests =====

    @Test
    fun `RetryInterceptor retries on 429 response`() {
        // First request returns 429, second returns success
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        // Verify that the request was made twice (original + retry)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `RetryInterceptor retries on 5xx responses`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `RetryInterceptor does NOT retry on 401 response`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
        // Only one request should be made (no retry)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `RetryInterceptor does NOT retry on 403 response`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(403, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `RetryInterceptor does NOT retry on other 4xx responses`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad request"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(400, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `RetryInterceptor respects Retry-After header on 429`() {
        // Return 429 with Retry-After header of 1 second
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setBody("Rate limited")
            .addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `RetryInterceptor throws RetryException after max retries exceeded`() {
        // All responses return 429, exhausting all retries
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        }

        val client = HttpClientFactory.createBaseClient(debugMode = false).build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()

        try {
            client.newCall(request).execute()
            org.junit.Assert.fail("Expected RetryException to be thrown")
        } catch (e: Exception) {
            assertTrue("Expected RetryException but got: ${e.javaClass.simpleName}",
                e is RetryException || e.cause is RetryException)
        }
    }

    // ===== AuthInterceptor Tests =====

    @Test
    fun `AuthInterceptor forOpenAi adds correct Authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val interceptor = AuthInterceptor.forOpenAi("my-openai-key")
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        client.newCall(request).execute()

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer my-openai-key", recordedRequest.getHeader("Authorization"))
    }

    // ===== AnthropicAuthInterceptor Tests =====

    @Test
    fun `AnthropicAuthInterceptor adds correct headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val interceptor = AnthropicAuthInterceptor("anthropic-secret")
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/test"))
            .get()
            .build()
        client.newCall(request).execute()

        val recordedRequest = server.takeRequest()
        assertEquals("anthropic-secret", recordedRequest.getHeader("x-api-key"))
        assertEquals("2023-06-01", recordedRequest.getHeader("anthropic-version"))
    }
}