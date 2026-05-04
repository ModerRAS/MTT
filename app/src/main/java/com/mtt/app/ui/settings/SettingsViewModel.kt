package com.mtt.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.remote.llm.LlmServiceFactory
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.remote.anthropic.AnthropicClient
import com.mtt.app.data.remote.openai.OpenAiClient
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.data.network.HttpClientFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SettingsScreen.
 * Manages API key, model, and proxy configuration for OpenAI and Anthropic providers.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val httpClientFactory: HttpClientFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Load saved settings from SecureStorage.
     */
    private fun loadSettings() {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI) ?: ""
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC) ?: ""
        
        val openAiModels = ModelRegistry.getByProvider(LlmProvider.OpenAI("", ""))
        val anthropicModels = ModelRegistry.getByProvider(LlmProvider.Anthropic("", ""))
        
        val savedOpenAiModelId = secureStorage.getApiKey("openai_model") ?: ModelRegistry.defaultOpenAiModel.modelId
        val savedAnthropicModelId = secureStorage.getApiKey("anthropic_model") ?: ModelRegistry.defaultAnthropicModel.modelId
        
        val selectedOpenAiModel = openAiModels.find { it.modelId == savedOpenAiModelId } ?: ModelRegistry.defaultOpenAiModel
        val selectedAnthropicModel = anthropicModels.find { it.modelId == savedAnthropicModelId } ?: ModelRegistry.defaultAnthropicModel
        
        _uiState.update { state ->
            state.copy(
                openAiSettings = ProviderSettings(
                    apiKey = openAiKey,
                    baseUrl = "https://api.openai.com/v1",
                    selectedModel = selectedOpenAiModel,
                    availableModels = openAiModels,
                    defaultModel = ModelRegistry.defaultOpenAiModel,
                    defaultBaseUrl = "https://api.openai.com/v1"
                ),
                anthropicSettings = ProviderSettings(
                    apiKey = anthropicKey,
                    baseUrl = "https://api.anthropic.com",
                    selectedModel = selectedAnthropicModel,
                    availableModels = anthropicModels,
                    defaultModel = ModelRegistry.defaultAnthropicModel,
                    defaultBaseUrl = "https://api.anthropic.com"
                )
            )
        }
    }

    /**
     * Update OpenAI API key.
     */
    fun updateOpenAiApiKey(key: String) {
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(
                    apiKey = key,
                    apiKeyError = validateApiKey(key)
                )
            )
        }
    }

    /**
     * Update OpenAI Base URL.
     */
    fun updateOpenAiBaseUrl(url: String) {
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(
                    baseUrl = url,
                    baseUrlError = validateUrl(url)
                )
            )
        }
    }

    /**
     * Update OpenAI selected model.
     */
    fun updateOpenAiModel(model: ModelInfo) {
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(
                    selectedModel = model
                )
            )
        }
    }

    /**
     * Update Anthropic API key.
     */
    fun updateAnthropicApiKey(key: String) {
        _uiState.update { state ->
            state.copy(
                anthropicSettings = state.anthropicSettings.copy(
                    apiKey = key,
                    apiKeyError = validateApiKey(key)
                )
            )
        }
    }

    /**
     * Update Anthropic Base URL.
     */
    fun updateAnthropicBaseUrl(url: String) {
        _uiState.update { state ->
            state.copy(
                anthropicSettings = state.anthropicSettings.copy(
                    baseUrl = url,
                    baseUrlError = validateUrl(url)
                )
            )
        }
    }

    /**
     * Update Anthropic selected model.
     */
    fun updateAnthropicModel(model: ModelInfo) {
        _uiState.update { state ->
            state.copy(
                anthropicSettings = state.anthropicSettings.copy(
                    selectedModel = model
                )
            )
        }
    }

    /**
     * Toggle OpenAI API key visibility.
     */
    fun toggleOpenAiKeyVisibility() {
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(
                    isKeyVisible = !state.openAiSettings.isKeyVisible
                )
            )
        }
    }

    /**
     * Toggle Anthropic API key visibility.
     */
    fun toggleAnthropicKeyVisibility() {
        _uiState.update { state ->
            state.copy(
                anthropicSettings = state.anthropicSettings.copy(
                    isKeyVisible = !state.anthropicSettings.isKeyVisible
                )
            )
        }
    }

    /**
     * Test OpenAI connection.
     */
    fun testOpenAiConnection() {
        val settings = _uiState.value.openAiSettings
        
        if (settings.apiKey.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    openAiSettings = state.openAiSettings.copy(
                        testConnectionState = TestConnectionState.Error("API key is required")
                    )
                )
            }
            return
        }
        
        if (settings.baseUrlError != null) {
            _uiState.update { state ->
                state.copy(
                    openAiSettings = state.openAiSettings.copy(
                        testConnectionState = TestConnectionState.Error("Invalid Base URL")
                    )
                )
            }
            return
        }
        
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(
                    testConnectionState = TestConnectionState.Testing
                )
            )
        }
        
        viewModelScope.launch {
            try {
                val okHttpClient = httpClientFactory.createBaseClient(debugMode = true).build()
                val openAiClient = OpenAiClient(okHttpClient, settings.apiKey, settings.baseUrl)
                val anthropicClient = AnthropicClient(okHttpClient, "", settings.baseUrl)
                
                val provider = LlmProvider.OpenAI(settings.apiKey, settings.baseUrl)
                val service = LlmServiceFactory.create(provider, openAiClient, anthropicClient)
                val result = service.testConnection()
                
                _uiState.update { state ->
                    state.copy(
                        openAiSettings = state.openAiSettings.copy(
                            testConnectionState = if (result) {
                                TestConnectionState.Success("Connection successful")
                            } else {
                                TestConnectionState.Error("Connection failed")
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        openAiSettings = state.openAiSettings.copy(
                            testConnectionState = TestConnectionState.Error(
                                e.message ?: "Unknown error occurred"
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Test Anthropic connection.
     */
    fun testAnthropicConnection() {
        val settings = _uiState.value.anthropicSettings
        
        if (settings.apiKey.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    anthropicSettings = state.anthropicSettings.copy(
                        testConnectionState = TestConnectionState.Error("API key is required")
                    )
                )
            }
            return
        }
        
        if (settings.baseUrlError != null) {
            _uiState.update { state ->
                state.copy(
                    anthropicSettings = state.anthropicSettings.copy(
                        testConnectionState = TestConnectionState.Error("Invalid Base URL")
                    )
                )
            }
            return
        }
        
        _uiState.update { state ->
            state.copy(
                anthropicSettings = state.anthropicSettings.copy(
                    testConnectionState = TestConnectionState.Testing
                )
            )
        }
        
        viewModelScope.launch {
            try {
                val okHttpClient = httpClientFactory.createBaseClient(debugMode = true).build()
                val openAiClient = OpenAiClient(okHttpClient, "", settings.baseUrl)
                val anthropicClient = AnthropicClient(okHttpClient, settings.apiKey, settings.baseUrl)
                
                val provider = LlmProvider.Anthropic(settings.apiKey, settings.baseUrl)
                val service = LlmServiceFactory.create(provider, openAiClient, anthropicClient)
                val result = service.testConnection()
                
                _uiState.update { state ->
                    state.copy(
                        anthropicSettings = state.anthropicSettings.copy(
                            testConnectionState = if (result) {
                                TestConnectionState.Success("Connection successful")
                            } else {
                                TestConnectionState.Error("Connection failed")
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        anthropicSettings = state.anthropicSettings.copy(
                            testConnectionState = TestConnectionState.Error(
                                e.message ?: "Unknown error occurred"
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Save settings to SecureStorage.
     * Called when leaving the settings screen.
     */
    fun saveSettings() {
        val state = _uiState.value
        
        // Save OpenAI settings
        if (state.openAiSettings.apiKey.isNotBlank()) {
            secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, state.openAiSettings.apiKey)
        } else {
            secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        }
        secureStorage.saveApiKey("openai_model", state.openAiSettings.selectedModel.modelId)
        
        // Save Anthropic settings
        if (state.anthropicSettings.apiKey.isNotBlank()) {
            secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, state.anthropicSettings.apiKey)
        } else {
            secureStorage.clearApiKey(SecureStorage.PROVIDER_ANTHROPIC)
        }
        secureStorage.saveApiKey("anthropic_model", state.anthropicSettings.selectedModel.modelId)
    }

    /**
     * Validate API key format.
     * @return Error message or null if valid
     */
    private fun validateApiKey(key: String): String? {
        return if (key.isBlank()) {
            "API key cannot be empty"
        } else {
            null
        }
    }

    /**
     * Validate URL format.
     * @return Error message or null if valid
     */
    private fun validateUrl(url: String): String? {
        if (url.isBlank()) {
            return "URL cannot be empty"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        return try {
            val uri = java.net.URI(url)
            if (uri.host.isNullOrBlank()) {
                "URL must have a valid host"
            } else {
                null
            }
        } catch (e: Exception) {
            "URL must have a valid host"
        }
    }
}

/**
 * UI state for SettingsScreen.
 */
data class SettingsUiState(
    val openAiSettings: ProviderSettings = ProviderSettings.createOpenAiSettings(),
    val anthropicSettings: ProviderSettings = ProviderSettings.createAnthropicSettings()
)

/**
 * Provider settings state (common for OpenAI and Anthropic).
 */
data class ProviderSettings(
    val apiKey: String = "",
    val apiKeyError: String? = null,
    val baseUrl: String,
    val baseUrlError: String? = null,
    val selectedModel: ModelInfo,
    val availableModels: List<ModelInfo> = emptyList(),
    val isKeyVisible: Boolean = false,
    val testConnectionState: TestConnectionState = TestConnectionState.Idle,
    val defaultModel: ModelInfo,
    val defaultBaseUrl: String
) {
    companion object {
        fun createOpenAiSettings(): ProviderSettings = ProviderSettings(
            baseUrl = "https://api.openai.com/v1",
            selectedModel = ModelRegistry.defaultOpenAiModel,
            defaultModel = ModelRegistry.defaultOpenAiModel,
            defaultBaseUrl = "https://api.openai.com/v1"
        )
        
        fun createAnthropicSettings(): ProviderSettings = ProviderSettings(
            baseUrl = "https://api.anthropic.com",
            selectedModel = ModelRegistry.defaultAnthropicModel,
            defaultModel = ModelRegistry.defaultAnthropicModel,
            defaultBaseUrl = "https://api.anthropic.com"
        )
    }
}

/**
 * Test connection state.
 */
sealed class TestConnectionState {
    object Idle : TestConnectionState()
    object Testing : TestConnectionState()
    data class Success(val message: String) : TestConnectionState()
    data class Error(val message: String) : TestConnectionState()
}