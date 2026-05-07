package com.mtt.app.data.remote.openai

import com.mtt.app.core.error.ApiException
import com.mtt.app.core.error.NetworkException
import com.mtt.app.data.model.LlmRequestConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Tests for OpenAiClient using OkHttp interceptor to simulate API responses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiClientTest {

    companion object {
        private const val TEST_API_KEY = "test-api-key"
        private const val TEST_BASE_URL = "https://api.test.com/v1"
        private const val TEST_MODEL = "gpt-4o-mini"
        private val SYSTEM_PROMPT = "Translate the following text."
        private val MESSAGES = listOf(
            LlmRequestConfig.Message(role = "user", content = "Hello, world!")
        )
    }

    /** Build an OkHttpClient with a mock interceptor that returns the given response. */
    private fun buildClientWithResponse(code: Int, body: String): OpenAiClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .build()
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)
    }

    @Test
    fun `translate success returns TranslationResponse`() {
        val deltaBody1 = """{"choices":[{"index":0,"delta":{"content":"Hola,"},"finish_reason":null}]}"""
        val deltaBody2 = """{"choices":[{"index":0,"delta":{"content":" mundo!"},"finish_reason":null}]}"""
        val usageBody = """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val sseBody = "data: $deltaBody1\n\ndata: $deltaBody2\n\ndata: $usageBody\n\ndata: [DONE]\n\n"
        val client = buildClientWithResponse(200, sseBody)
        val result = client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
        assertEquals("Hola, mundo!", result.content)
        assertEquals(TEST_MODEL, result.model)
        assertEquals(15, result.tokensUsed)
    }

    @Test
    fun `translate when IOException thrown then throws NetworkException`() {
        val interceptor = Interceptor { _ ->
            throw IOException("Unable to resolve host")
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val client = OpenAiClient(okHttpClient, TEST_API_KEY, TEST_BASE_URL)

        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected NetworkException to be thrown")
        } catch (e: NetworkException) {
            assertTrue(e.userMessage.startsWith("网络连接失败，请检查网络"))
        }
    }

    @Test
    fun `translate when 401 error then throws ApiException authFailure`() {
        val client = buildClientWithResponse(401, """{"error":{"code":"401","message":"Unauthorized"}}""")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("API Key 无效，请检查设置", e.userMessage)
        }
    }

    @Test
    fun `translate when 429 error then throws ApiException rateLimit`() {
        val client = buildClientWithResponse(429, """{"error":{"code":"429","message":"Rate limit"}}""")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 500 error then throws ApiException serverError`() {
        val client = buildClientWithResponse(500, "Internal Server Error")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 5xx error then throws ApiException serverError`() {
        val client = buildClientWithResponse(502, "Bad Gateway")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("服务器错误，请稍后重试", e.userMessage)
        }
    }

    @Test
    fun `translate when 403 error then throws ApiException forbidden`() {
        val client = buildClientWithResponse(403, """{"error":{"code":"403","message":"Forbidden"}}""")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("权限不足", e.userMessage)
        }
    }

    @Test
    fun `translate when unknown error then throws ApiException`() {
        val client = buildClientWithResponse(418, "Teapot")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(418, e.code)
        }
    }

    @Test
    fun `translate when unauthorized keyword then throws ApiException authFailure`() {
        val client = buildClientWithResponse(401, """{"error":{"message":"unauthorized access"}}""")
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
        val client = buildClientWithResponse(429, """{"error":{"message":"rate limit"}}""")
        try {
            client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL)
            fail("Expected ApiException to be thrown")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertEquals("请求过于频繁，请稍后重试", e.userMessage)
        }
    }

    // ── Tool call (SSE with tool_calls delta) ─────────────────

    @Test
    fun `translate with toolChoice parses object array tool calls`() {
        val chunk1 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"output_translations","arguments":""}}]}}]}"""
        val chunk2 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"transl"}}]}}]}"""
        val chunk3 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ations\":[{\"source\":\"Hello\",\"translated\":\"你好\"},{\"source\":\"World\",\"translated\":\"世界\"}]}}"}}]}}]}"""
        val usage = """{"choices":[],"usage":{"total_tokens":42}}"""
        val sseBody = "data: $chunk1\n\ndata: $chunk2\n\ndata: $chunk3\n\ndata: $usage\n\ndata: [DONE]\n\n"
        val client = buildClientWithResponse(200, sseBody)

        val result = client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL, toolChoice = "output_translations")

        assertEquals(42, result.tokensUsed)
        assertEquals(2, result.translationPairs?.size)
        assertEquals("Hello", result.translationPairs?.get(0)?.source)
        assertEquals("你好", result.translationPairs?.get(0)?.translated)
        assertEquals("World", result.translationPairs?.get(1)?.source)
        assertEquals("世界", result.translationPairs?.get(1)?.translated)
    }

    @Test
    fun `translate with toolChoice parses flat string array tool calls`() {
        val chunk1 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"output_translations","arguments":""}}]}}]}"""
        val chunk2 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"transl"}}]}}]}"""
        val chunk3 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ations\":[\"你好\",\"世界\"]}"}}]}}]}"""
        val sseBody = "data: $chunk1\n\ndata: $chunk2\n\ndata: $chunk3\n\ndata: [DONE]\n\n"
        val client = buildClientWithResponse(200, sseBody)

        val result = client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL, toolChoice = "output_translations")

        assertEquals(2, result.translations?.size)
        assertEquals("你好", result.translations?.get(0))
        assertEquals("世界", result.translations?.get(1))
        assertEquals(null, result.translationPairs) // flat array, no pairs
    }

    @Test
    fun `translate with toolChoice handles single pair tool call`() {
        val toolCallData = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"output_translations","arguments":"{\"translations\":[{\"source\":\"Hello\",\"translated\":\"你好\"}]}"}}]}}]}"""
        val sseBody = "data: $toolCallData\n\ndata: [DONE]\n\n"
        val client = buildClientWithResponse(200, sseBody)

        val result = client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL, toolChoice = "output_translations")

        assertEquals(1, result.translationPairs?.size)
        assertEquals("Hello", result.translationPairs?.get(0)?.source)
        assertEquals("你好", result.translationPairs?.get(0)?.translated)
    }

    @Test
    fun `translate with toolChoice handles malformed json gracefully`() {
        // Malformed JSON in tool call arguments — should not throw
        val chunk1 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"not valid json"}}]}}]}"""
        val sseBody = "data: $chunk1\n\ndata: [DONE]\n\n"
        val client = buildClientWithResponse(200, sseBody)

        val result = client.translate(MESSAGES, SYSTEM_PROMPT, TEST_MODEL, toolChoice = "output_translations")

        // Should not throw, translations/pairs should be null
        assertEquals(null, result.translations)
        assertEquals(null, result.translationPairs)
    }
}
