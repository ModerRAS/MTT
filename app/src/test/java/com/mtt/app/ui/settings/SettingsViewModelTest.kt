package com.mtt.app.ui.settings

import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.data.network.HttpClientFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var secureStorage: SecureStorage
    private lateinit var httpClientFactory: HttpClientFactory
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        secureStorage = mockk(relaxed = true)
        httpClientFactory = mockk(relaxed = true)
        
        // Mock empty storage by default
        every { secureStorage.getApiKey(any()) } returns null
        
        viewModel = SettingsViewModel(secureStorage, httpClientFactory)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads empty settings`() {
        val state = viewModel.uiState.value
        
        assertEquals("", state.openAiSettings.apiKey)
        assertEquals("https://api.openai.com/v1", state.openAiSettings.baseUrl)
        assertEquals(ModelRegistry.defaultOpenAiModel, state.openAiSettings.selectedModel)
        assertTrue(state.openAiSettings.availableModels.isNotEmpty())
        
        assertEquals("", state.anthropicSettings.apiKey)
        assertEquals("https://api.anthropic.com", state.anthropicSettings.baseUrl)
        assertEquals(ModelRegistry.defaultAnthropicModel, state.anthropicSettings.selectedModel)
        assertTrue(state.anthropicSettings.availableModels.isNotEmpty())
    }

    @Test
    fun `initial state loads saved settings`() {
        every { secureStorage.getApiKey("openai") } returns "saved-openai-key"
        every { secureStorage.getApiKey("anthropic") } returns "saved-anthropic-key"
        every { secureStorage.getApiKey("openai_model") } returns "gpt-4o"
        every { secureStorage.getApiKey("anthropic_model") } returns "claude-3-5-sonnet-20241022"
        
        val viewModel = SettingsViewModel(secureStorage, openAiClient, anthropicClient)
        val state = viewModel.uiState.value
        
        assertEquals("saved-openai-key", state.openAiSettings.apiKey)
        assertEquals("gpt-4o", state.openAiSettings.selectedModel.modelId)
        
        assertEquals("saved-anthropic-key", state.anthropicSettings.apiKey)
        assertEquals("claude-3-5-sonnet-20241022", state.anthropicSettings.selectedModel.modelId)
    }

    @Test
    fun `updateOpenAiApiKey updates state and validates`() {
        viewModel.updateOpenAiApiKey("test-key")
        
        val state = viewModel.uiState.value
        assertEquals("test-key", state.openAiSettings.apiKey)
        assertNull(state.openAiSettings.apiKeyError)
    }

    @Test
    fun `updateOpenAiApiKey shows error for empty key`() {
        viewModel.updateOpenAiApiKey("")
        
        val state = viewModel.uiState.value
        assertEquals("", state.openAiSettings.apiKey)
        assertEquals("API key cannot be empty", state.openAiSettings.apiKeyError)
    }

    @Test
    fun `updateOpenAiBaseUrl updates state and validates`() {
        viewModel.updateOpenAiBaseUrl("https://custom.openai.com/v1")
        
        val state = viewModel.uiState.value
        assertEquals("https://custom.openai.com/v1", state.openAiSettings.baseUrl)
        assertNull(state.openAiSettings.baseUrlError)
    }

    @Test
    fun `updateOpenAiBaseUrl shows error for invalid URL`() {
        viewModel.updateOpenAiBaseUrl("not-a-url")
        
        val state = viewModel.uiState.value
        assertEquals("not-a-url", state.openAiSettings.baseUrl)
        assertEquals("URL must start with http:// or https://", state.openAiSettings.baseUrlError)
    }

    @Test
    fun `updateOpenAiBaseUrl shows error for URL without host`() {
        viewModel.updateOpenAiBaseUrl("https://")
        
        val state = viewModel.uiState.value
        assertEquals("https://", state.openAiSettings.baseUrl)
        assertEquals("URL must have a valid host", state.openAiSettings.baseUrlError)
    }

    @Test
    fun `updateOpenAiModel updates selected model`() {
        val newModel = ModelRegistry.GPT_4O
        viewModel.updateOpenAiModel(newModel)
        
        val state = viewModel.uiState.value
        assertEquals(newModel, state.openAiSettings.selectedModel)
    }

    @Test
    fun `updateAnthropicApiKey updates state and validates`() {
        viewModel.updateAnthropicApiKey("test-key")
        
        val state = viewModel.uiState.value
        assertEquals("test-key", state.anthropicSettings.apiKey)
        assertNull(state.anthropicSettings.apiKeyError)
    }

    @Test
    fun `updateAnthropicApiKey shows error for empty key`() {
        viewModel.updateAnthropicApiKey("")
        
        val state = viewModel.uiState.value
        assertEquals("", state.anthropicSettings.apiKey)
        assertEquals("API key cannot be empty", state.anthropicSettings.apiKeyError)
    }

    @Test
    fun `updateAnthropicBaseUrl updates state and validates`() {
        viewModel.updateAnthropicBaseUrl("https://custom.anthropic.com")
        
        val state = viewModel.uiState.value
        assertEquals("https://custom.anthropic.com", state.anthropicSettings.baseUrl)
        assertNull(state.anthropicSettings.baseUrlError)
    }

    @Test
    fun `updateAnthropicBaseUrl shows error for invalid URL`() {
        viewModel.updateAnthropicBaseUrl("ftp://invalid.com")
        
        val state = viewModel.uiState.value
        assertEquals("ftp://invalid.com", state.anthropicSettings.baseUrl)
        assertEquals("URL must start with http:// or https://", state.anthropicSettings.baseUrlError)
    }

    @Test
    fun `updateAnthropicModel updates selected model`() {
        val newModel = ModelRegistry.CLAUDE_SONNET
        viewModel.updateAnthropicModel(newModel)
        
        val state = viewModel.uiState.value
        assertEquals(newModel, state.anthropicSettings.selectedModel)
    }

    @Test
    fun `toggleOpenAiKeyVisibility toggles visibility`() {
        assertFalse(viewModel.uiState.value.openAiSettings.isKeyVisible)
        
        viewModel.toggleOpenAiKeyVisibility()
        assertTrue(viewModel.uiState.value.openAiSettings.isKeyVisible)
        
        viewModel.toggleOpenAiKeyVisibility()
        assertFalse(viewModel.uiState.value.openAiSettings.isKeyVisible)
    }

    @Test
    fun `toggleAnthropicKeyVisibility toggles visibility`() {
        assertFalse(viewModel.uiState.value.anthropicSettings.isKeyVisible)
        
        viewModel.toggleAnthropicKeyVisibility()
        assertTrue(viewModel.uiState.value.anthropicSettings.isKeyVisible)
        
        viewModel.toggleAnthropicKeyVisibility()
        assertFalse(viewModel.uiState.value.anthropicSettings.isKeyVisible)
    }

    @Test
    fun `testOpenAiConnection shows error for empty API key`() = runTest {
        viewModel.testOpenAiConnection()
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertTrue(state.openAiSettings.testConnectionState is TestConnectionState.Error)
        assertEquals(
            "API key is required",
            (state.openAiSettings.testConnectionState as TestConnectionState.Error).message
        )
    }

    @Test
    fun `testOpenAiConnection shows error for invalid URL`() = runTest {
        viewModel.updateOpenAiApiKey("test-key")
        viewModel.updateOpenAiBaseUrl("not-a-url")
        
        viewModel.testOpenAiConnection()
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertTrue(state.openAiSettings.testConnectionState is TestConnectionState.Error)
        assertEquals(
            "Invalid Base URL",
            (state.openAiSettings.testConnectionState as TestConnectionState.Error).message
        )
    }

    @Test
    fun `testAnthropicConnection shows error for empty API key`() = runTest {
        viewModel.testAnthropicConnection()
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertTrue(state.anthropicSettings.testConnectionState is TestConnectionState.Error)
        assertEquals(
            "API key is required",
            (state.anthropicSettings.testConnectionState as TestConnectionState.Error).message
        )
    }

    @Test
    fun `testAnthropicConnection shows error for invalid URL`() = runTest {
        viewModel.updateAnthropicApiKey("test-key")
        viewModel.updateAnthropicBaseUrl("not-a-url")
        
        viewModel.testAnthropicConnection()
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertTrue(state.anthropicSettings.testConnectionState is TestConnectionState.Error)
        assertEquals(
            "Invalid Base URL",
            (state.anthropicSettings.testConnectionState as TestConnectionState.Error).message
        )
    }

    @Test
    fun `saveSettings saves OpenAI settings`() {
        viewModel.updateOpenAiApiKey("openai-key")
        viewModel.updateOpenAiModel(ModelRegistry.GPT_4O)
        
        viewModel.saveSettings()
        
        verify { secureStorage.saveApiKey("openai", "openai-key") }
        verify { secureStorage.saveApiKey("openai_model", "gpt-4o") }
    }

    @Test
    fun `saveSettings clears empty OpenAI key`() {
        viewModel.updateOpenAiApiKey("")
        
        viewModel.saveSettings()
        
        verify { secureStorage.clearApiKey("openai") }
    }

    @Test
    fun `saveSettings saves Anthropic settings`() {
        viewModel.updateAnthropicApiKey("anthropic-key")
        viewModel.updateAnthropicModel(ModelRegistry.CLAUDE_SONNET)
        
        viewModel.saveSettings()
        
        verify { secureStorage.saveApiKey("anthropic", "anthropic-key") }
        verify { secureStorage.saveApiKey("anthropic_model", "claude-3-5-sonnet-20241022") }
    }

    @Test
    fun `saveSettings clears empty Anthropic key`() {
        viewModel.updateAnthropicApiKey("")
        
        viewModel.saveSettings()
        
        verify { secureStorage.clearApiKey("anthropic") }
    }

    @Test
    fun `available models are filtered by provider`() {
        val state = viewModel.uiState.value
        
        val openAiModels = state.openAiSettings.availableModels
        val anthropicModels = state.anthropicSettings.availableModels
        
        assertTrue(openAiModels.all { it.modelId.startsWith("gpt-") })
        assertTrue(anthropicModels.all { it.modelId.startsWith("claude-") })
    }

    @Test
    fun `default models are set correctly`() {
        val state = viewModel.uiState.value
        
        assertEquals(ModelRegistry.defaultOpenAiModel, state.openAiSettings.selectedModel)
        assertEquals(ModelRegistry.defaultAnthropicModel, state.anthropicSettings.selectedModel)
    }
}