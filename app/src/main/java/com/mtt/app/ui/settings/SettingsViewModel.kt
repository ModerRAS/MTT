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
import org.json.JSONArray
import org.json.JSONObject
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
        loadCustomModels()
        loadSettings()
    }

    /**
     * Load custom models from SecureStorage and populate ModelRegistry.
     */
    private fun loadCustomModels() {
        val json = secureStorage.getCustomModels()
        if (json != null) {
            try {
                val models = parseCustomModelsJson(json)
                ModelRegistry.initCustomModels(models)
            } catch (_: Exception) {
                // If JSON parsing fails, just use defaults
            }
        }
    }

    /**
     * Parse custom models from saved JSON string.
     */
    private fun parseCustomModelsJson(json: String): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val modelId = obj.getString("modelId")
            val displayName = obj.optString("displayName", modelId)
            val contextWindow = obj.optInt("contextWindow", 128000)
            val providerName = obj.optString("provider", "openai")
            val provider = if (providerName == "anthropic") {
                LlmProvider.Anthropic("", "")
            } else {
                LlmProvider.OpenAI("", "")
            }
            models.add(ModelRegistry.createCustom(modelId, displayName, contextWindow, provider))
        }
        return models
    }

    /**
     * Serialize custom models to JSON string for persistence.
     */
    private fun serializeCustomModels(): String {
        val arr = JSONArray()
        for (model in ModelRegistry.customModels) {
            val obj = JSONObject()
            obj.put("modelId", model.modelId)
            obj.put("displayName", model.displayName)
            obj.put("contextWindow", model.contextWindow)
            obj.put("provider", if (model.provider is LlmProvider.Anthropic) "anthropic" else "openai")
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Load saved settings from SecureStorage.
     */
    private fun loadSettings() {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI) ?: ""
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC) ?: ""
        
        val openAiModels = ModelRegistry.getByProvider(LlmProvider.OpenAI("", ""))
        val anthropicModels = ModelRegistry.getByProvider(LlmProvider.Anthropic("", ""))
        
        // Use getApiKey for backward compatibility (getApiKey transforms "openai_model" -> "key_openai_model")
        val savedOpenAiModelId = secureStorage.getApiKey(SecureStorage.KEY_OPENAI_MODEL)
            ?: ModelRegistry.defaultOpenAiModel.modelId
        val savedAnthropicModelId = secureStorage.getApiKey(SecureStorage.KEY_ANTHROPIC_MODEL)
            ?: ModelRegistry.defaultAnthropicModel.modelId
        
        val selectedOpenAiModel = openAiModels.find { it.modelId == savedOpenAiModelId }
            ?: ModelRegistry.defaultOpenAiModel
        val selectedAnthropicModel = anthropicModels.find { it.modelId == savedAnthropicModelId }
            ?: ModelRegistry.defaultAnthropicModel
        
        _uiState.update { state ->
            state.copy(
                openAiSettings = ProviderSettings(
                    apiKey = openAiKey,
                    baseUrl = secureStorage.getValue(SecureStorage.KEY_OPENAI_BASE_URL) ?: "https://api.deepseek.com",
                    selectedModel = selectedOpenAiModel,
                    availableModels = openAiModels,
                    defaultModel = ModelRegistry.defaultOpenAiModel,
                    defaultBaseUrl = "https://api.openai.com/v1"
                ),
                anthropicSettings = ProviderSettings(
                    apiKey = anthropicKey,
                    baseUrl = secureStorage.getValue(SecureStorage.KEY_ANTHROPIC_BASE_URL) ?: "https://api.anthropic.com",
                    selectedModel = selectedAnthropicModel,
                    availableModels = anthropicModels,
                    defaultModel = ModelRegistry.defaultAnthropicModel,
                    defaultBaseUrl = "https://api.anthropic.com"
                )
            )
        }
    }

    /**
     * Refresh available models list (e.g., after adding a custom model).
     */
    private fun refreshModels() {
        val openAiModels = ModelRegistry.getByProvider(LlmProvider.OpenAI("", ""))
        val anthropicModels = ModelRegistry.getByProvider(LlmProvider.Anthropic("", ""))
        _uiState.update { state ->
            state.copy(
                openAiSettings = state.openAiSettings.copy(availableModels = openAiModels),
                anthropicSettings = state.anthropicSettings.copy(availableModels = anthropicModels)
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
     * Supports both preset and custom model selection by modelId string.
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
     * Update OpenAI selected model by modelId (used when user types a custom model name).
     * If the modelId matches a known model, use that. Otherwise create a custom ModelInfo.
     */
    fun updateOpenAiModelById(modelId: String) {
        val existing = ModelRegistry.allModels.firstOrNull { it.modelId == modelId }
        if (existing != null && existing.provider is LlmProvider.OpenAI) {
            updateOpenAiModel(existing)
        } else {
            // Create a custom/openai ModelInfo or find existing one
            val custom = ModelRegistry.customModels.firstOrNull {
                it.modelId == modelId && it.provider is LlmProvider.OpenAI
            }
            if (custom != null) {
                updateOpenAiModel(custom)
            } else if (modelId.isNotBlank()) {
                // Create temporary custom model in-memory
                val newModel = ModelRegistry.createCustom(
                    modelId = modelId,
                    displayName = modelId,
                    provider = LlmProvider.OpenAI("", "")
                )
                updateOpenAiModel(newModel)
            }
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
     * Update Anthropic selected model by modelId (used when user types a custom model name).
     */
    fun updateAnthropicModelById(modelId: String) {
        val existing = ModelRegistry.allModels.firstOrNull { it.modelId == modelId }
        if (existing != null && existing.provider is LlmProvider.Anthropic) {
            updateAnthropicModel(existing)
        } else {
            val custom = ModelRegistry.customModels.firstOrNull {
                it.modelId == modelId && it.provider is LlmProvider.Anthropic
            }
            if (custom != null) {
                updateAnthropicModel(custom)
            } else if (modelId.isNotBlank()) {
                val newModel = ModelRegistry.createCustom(
                    modelId = modelId,
                    displayName = modelId,
                    provider = LlmProvider.Anthropic("", "")
                )
                updateAnthropicModel(newModel)
            }
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

    // ── Custom Model Management ────────────────────

    /**
     * Add a custom model with the given parameters and persist it.
     */
    fun addCustomModel(modelId: String, displayName: String, contextWindow: Int, isAnthropic: Boolean) {
        val provider = if (isAnthropic) LlmProvider.Anthropic("", "") else LlmProvider.OpenAI("", "")
        val model = ModelRegistry.createCustom(modelId, displayName, contextWindow, provider)
        ModelRegistry.addCustomModel(model)
        saveCustomModels()
        refreshModels()
        // Auto-select the newly added model
        if (isAnthropic) {
            updateAnthropicModel(model)
        } else {
            updateOpenAiModel(model)
        }
    }

    /**
     * Remove a custom model and persist the change.
     */
    fun removeCustomModel(modelId: String) {
        ModelRegistry.removeCustomModel(modelId)
        saveCustomModels()
        refreshModels()
    }

    /**
     * Persist custom models to SecureStorage.
     */
    private fun saveCustomModels() {
        val json = serializeCustomModels()
        secureStorage.saveCustomModels(json)
    }

    // ── Test Connection ───────────────────────────

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
                val result = service.testConnection(settings.selectedModel.modelId)
                
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
                val result = service.testConnection(settings.selectedModel.modelId)
                
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
        // Use saveApiKey for backward compatibility (transform "openai_model" -> "key_openai_model")
        secureStorage.saveApiKey(SecureStorage.KEY_OPENAI_MODEL, state.openAiSettings.selectedModel.modelId)
        secureStorage.saveValue(SecureStorage.KEY_OPENAI_BASE_URL, state.openAiSettings.baseUrl)
        
        // Save Anthropic settings
        if (state.anthropicSettings.apiKey.isNotBlank()) {
            secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, state.anthropicSettings.apiKey)
        } else {
            secureStorage.clearApiKey(SecureStorage.PROVIDER_ANTHROPIC)
        }
        secureStorage.saveApiKey(SecureStorage.KEY_ANTHROPIC_MODEL, state.anthropicSettings.selectedModel.modelId)
        secureStorage.saveValue(SecureStorage.KEY_ANTHROPIC_BASE_URL, state.anthropicSettings.baseUrl)
        
        // Save custom models
        saveCustomModels()
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
            baseUrl = "https://api.deepseek.com",
            selectedModel = ModelRegistry.defaultOpenAiModel,
            defaultModel = ModelRegistry.defaultOpenAiModel,
            defaultBaseUrl = "https://api.deepseek.com"
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
