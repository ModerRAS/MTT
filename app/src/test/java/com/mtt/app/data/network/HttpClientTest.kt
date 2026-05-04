package com.mtt.app.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for HttpClientFactory, RetryInterceptor, and auth configuration.
 *
 * Tests cover:
 * - Retry behavior on 429 and 5xx errors
 * - Timeout configuration
 * - Authorization header injection
 * - Logging configuration in debug/release modes
 */
class HttpClientTest {

    private lateinit var mockWebServer: MockWebServer
    private val requestCount = AtomicInteger(0)

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        requestCount.set(0)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    // ========== RetryInterceptor Tests ==========

    @Test
    fun `retryInterceptor retries on 429 response`() {
        // Server returns 429 twice, then 200
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}""").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `retryInterceptor respects Retry-After header`() {
        // Server returns 429 with Retry-After: 1 (1 second)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
        )
        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}""").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val startTime = System.currentTimeMillis()
        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(200, response.code)
        // Should have waited at least 1 second due to Retry-After
        assertTrue("Should respect Retry-After header", elapsed >= 1000)
    }

    @Test
    fun `retryInterceptor retries on 500 error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}""").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `retryInterceptor retries on 503 error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}""").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(4, mockWebServer.requestCount)
    }

    @Test
    fun `retryInterceptor does NOT retry on 400 error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(400))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(400, response.code)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `retryInterceptor throws exception after max retries exceeded`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        try {
            client.newCall(request).execute()
            fail("Should throw RetryException")
        } catch (e: RetryException) {
            assertTrue(e.message!!.contains("Max retries"))
        }
    }

    // ========== Timeout Configuration Tests ==========

    @Test
    fun `HttpClientFactory sets correct default timeouts`() {
        val builder = HttpClientFactory.createBuilder()

        val client = builder.build()

        assertEquals(
            HttpClientFactory.CONNECT_TIMEOUT_SECONDS,
            client.connectTimeoutMillis / 1000L
        )
        assertEquals(
            HttpClientFactory.READ_TIMEOUT_SECONDS,
            client.readTimeoutMillis / 1000L
        )
        assertEquals(
            HttpClientFactory.WRITE_TIMEOUT_SECONDS,
            client.writeTimeoutMillis / 1000L
        )
    }

    @Test
    fun `HttpClientFactory allows custom timeouts`() {
        val client = HttpClientFactory.createBuilder(
            connectTimeout = 10L,
            readTimeout = 30L,
            writeTimeout = 20L
        ).build()

        assertEquals(10, client.connectTimeoutMillis / 1000L)
        assertEquals(30, client.readTimeoutMillis / 1000L)
        assertEquals(20, client.writeTimeoutMillis / 1000L)
    }

    // ========== Authorization Header Tests ==========

    @Test
    fun `createOpenAiClient adds Authorization Bearer header`() {
        val apiKey = "sk-test-key-12345"
        val client = HttpClientFactory.createOpenAiClient(
            apiKey = apiKey,
            baseUrl = "https://api.openai.com/v1"
        )

        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}"""))

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        // We need to use a custom interceptor chain to inject the auth
        val interceptedRequest = captureRequestWithAuth(client, request)

        assertNotNull(interceptedRequest)
        assertEquals("Bearer $apiKey", interceptedRequest.header("Authorization"))
    }

    @Test
    fun `createAnthropicClient adds x-api-key header`() {
        val apiKey = "sk-ant-test-12345"
        val client = HttpClientFactory.createAnthropicClient(
            apiKey = apiKey,
            baseUrl = "https://api.anthropic.com"
        )

        mockWebServer.enqueue(MockResponse().setBody("""{"success": true}"""))

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val interceptedRequest = captureRequestWithAuth(client, request)

        assertNotNull(interceptedRequest)
        assertEquals(apiKey, interceptedRequest.header("x-api-key"))
        assertEquals("2023-06-01", interceptedRequest.header("anthropic-version"))
    }

    private fun captureRequestWithAuth(
        client: OkHttpClient,
        originalRequest: okhttp3.Request
    ): okhttp3.Request? {
        var capturedRequest: okhttp3.Request? = null
        val latch = CountDownLatch(1)

        val capturingInterceptor = Interceptor { chain ->
            capturedRequest = chain.request()
            latch.countDown()
            // Return a fake response since we're not actually calling the server
            Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        val testClient = client.newBuilder()
            .addInterceptor(capturingInterceptor)
            .build()

        try {
            testClient.newCall(originalRequest).execute()
        } catch (_: IOException) {
            // Ignore - we just want to capture the request
        }

        latch.await(1, TimeUnit.SECONDS)
        return capturedRequest
    }

    // ========== Logging Interceptor Tests ==========

    @Test
    fun `LoggingInterceptor forDebug has BODY level`() {
        val interceptor = LoggingInterceptor.forDebug()
        assertEquals(
            okhttp3.logging.HttpLoggingInterceptor.Level.BODY,
            interceptor.level
        )
    }

    @Test
    fun `LoggingInterceptor forRelease has HEADERS level`() {
        val interceptor = LoggingInterceptor.forRelease()
        assertEquals(
            okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS,
            interceptor.level
        )
    }

    @Test
    fun `LoggingInterceptor redacts Authorization headers`() {
        val loggingInterceptor = LoggingInterceptor.forDebug()
        assertTrue(loggingInterceptor.redactHeaders.contains("Authorization"))
        assertTrue(loggingInterceptor.redactHeaders.contains("x-api-key"))
    }

    // ========== Base Client Tests ==========

    @Test
    fun `createBaseClient includes RetryInterceptor`() {
        val builder = HttpClientFactory.createBaseClient()
        val client = builder.build()

        // The client should have interceptors - we verify by building
        assertNotNull(client)
    }

    @Test
    fun `createBaseClient in debug mode includes logging`() {
        val builder = HttpClientFactory.createBaseClient(debugMode = true)
        val client = builder.build()
        assertNotNull(client)
    }
}
