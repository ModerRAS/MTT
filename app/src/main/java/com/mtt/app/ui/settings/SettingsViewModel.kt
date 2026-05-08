package com.mtt.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.core.error.Result
import com.mtt.app.data.model.ChannelConfig
import com.mtt.app.data.model.ChannelType
import com.mtt.app.data.model.FetchedModel
import com.mtt.app.data.remote.ModelFetcher
import com.mtt.app.data.security.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for SettingsScreen.
 *
 * Manages a multi-channel LLM provider system:
 * - Add/edit/delete arbitrary LLM channels (OpenAI/Anthropic)
 * - Fetch available models from each channel's API
 * - Set active channel and active model
 * - Configure batch size and concurrency for translation pipeline
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val modelFetcher: ModelFetcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadChannels()
        loadSettings()
    }

    // ── Data Loading ──────────────────────────────

    /**
     * Load channels and active IDs from SecureStorage.
     */
    fun loadChannels() {
        val channels = secureStorage.loadChannels()
        val activeChannelId = secureStorage.loadActiveChannelId()
        val activeModelId = secureStorage.loadActiveModelId() ?: ""
        _uiState.update { it.copy(
            channels = channels,
            activeChannelId = activeChannelId,
            activeModelId = activeModelId
        )}
    }

    /**
     * Load pipeline settings (batch size, concurrency) from SecureStorage.
     */
    fun loadSettings() {
        val batchSize = secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE)
            ?.toIntOrNull() ?: 50
        val concurrency = secureStorage.getValue(SecureStorage.KEY_CONCURRENCY)
            ?.toIntOrNull() ?: 1
        _uiState.update { it.copy(
            batchSize = batchSize.coerceIn(1, 200),
            concurrency = concurrency.coerceIn(1, 10)
        )}
    }

    /**
     * Persist pipeline settings to SecureStorage.
     * Called via DisposableEffect on screen exit.
     */
    fun saveSettings() {
        val state = _uiState.value
        secureStorage.saveValue(SecureStorage.KEY_BATCH_SIZE, state.batchSize.toString())
        secureStorage.saveValue(SecureStorage.KEY_CONCURRENCY, state.concurrency.toString())
    }

    // ── Active Config ─────────────────────────────

    /**
     * Set the active channel and persist.
     */
    fun setActiveChannel(id: String) {
        secureStorage.saveActiveChannelId(id)
        _uiState.update { it.copy(activeChannelId = id) }
    }

    /**
     * Set the active model and persist.
     */
    fun setActiveModel(modelId: String) {
        secureStorage.saveActiveModelId(modelId)
        _uiState.update { it.copy(activeModelId = modelId) }
    }

    // ── Channel CRUD ──────────────────────────────

    /**
     * Toggle the "add channel" dialog visibility.
     */
    fun toggleAddChannel() {
        val wasAdding = _uiState.value.isAddingChannel
        _uiState.update { it.copy(
            isAddingChannel = !it.isAddingChannel,
            newChannelForm = if (wasAdding) it.newChannelForm else ChannelFormState()
        )}
    }

    /**
     * Start editing an existing channel — populate form from channel data.
     */
    fun startEditChannel(id: String) {
        val channel = _uiState.value.channels.find { it.id == id } ?: return
        _uiState.update { it.copy(
            editingChannelId = id,
            newChannelForm = ChannelFormState(
                name = channel.name,
                type = channel.type,
                baseUrl = channel.baseUrl,
                apiKey = channel.apiKey,
                apiKeyVisible = false
            )
        )}
    }

    /**
     * Cancel the edit channel dialog.
     */
    fun cancelEditChannel() {
        _uiState.update { it.copy(
            editingChannelId = null,
            newChannelForm = ChannelFormState()
        )}
    }

    // ── Form Field Updates ────────────────────────

    fun updateFormName(name: String) {
        _uiState.update { it.copy(
            newChannelForm = it.newChannelForm.copy(name = name)
        )}
    }

    fun updateFormType(type: ChannelType) {
        _uiState.update { it.copy(
            newChannelForm = it.newChannelForm.copy(type = type)
        )}
    }

    fun updateFormBaseUrl(url: String) {
        _uiState.update { it.copy(
            newChannelForm = it.newChannelForm.copy(baseUrl = url)
        )}
    }

    fun updateFormApiKey(key: String) {
        _uiState.update { it.copy(
            newChannelForm = it.newChannelForm.copy(apiKey = key)
        )}
    }

    fun toggleFormApiKeyVisibility() {
        _uiState.update { it.copy(
            newChannelForm = it.newChannelForm.copy(
                apiKeyVisible = !it.newChannelForm.apiKeyVisible
            )
        )}
    }

    // ── Add / Update / Delete Channel ─────────────

    /**
     * Add a new channel from the form state.
     * Validates required fields, creates a ChannelConfig, persists.
     */
    fun addChannel() {
        val form = _uiState.value.newChannelForm
        if (form.name.isBlank() || form.baseUrl.isBlank() || form.apiKey.isBlank()) {
            _uiState.update { it.copy(
                globalMessage = "请填写所有必填项（名称、URL、API Key）"
            )}
            return
        }

        val newChannel = ChannelConfig(
            id = UUID.randomUUID().toString(),
            name = form.name.trim(),
            type = form.type,
            baseUrl = form.baseUrl.trimEnd('/'),
            apiKey = form.apiKey.trim(),
            fetchedModels = emptyList(),
            fetchedAt = null
        )

        val updatedChannels = _uiState.value.channels + newChannel
        secureStorage.saveChannels(updatedChannels)
        _uiState.update { it.copy(
            channels = updatedChannels,
            isAddingChannel = false,
            newChannelForm = ChannelFormState(),
            globalMessage = "渠道 \"${newChannel.name}\" 已添加"
        )}
    }

    /**
     * Update an existing channel from the form state.
     */
    fun updateChannel(id: String) {
        val form = _uiState.value.newChannelForm
        if (form.name.isBlank() || form.baseUrl.isBlank() || form.apiKey.isBlank()) {
            _uiState.update { it.copy(
                globalMessage = "请填写所有必填项（名称、URL、API Key）"
            )}
            return
        }

        val updatedChannels = _uiState.value.channels.map { channel ->
            if (channel.id == id) {
                channel.copy(
                    name = form.name.trim(),
                    type = form.type,
                    baseUrl = form.baseUrl.trimEnd('/'),
                    apiKey = form.apiKey.trim()
                )
            } else {
                channel
            }
        }
        secureStorage.saveChannels(updatedChannels)
        _uiState.update { it.copy(
            channels = updatedChannels,
            editingChannelId = null,
            newChannelForm = ChannelFormState(),
            globalMessage = "渠道 \"${form.name.trim()}\" 已更新"
        )}
    }

    /**
     * Delete a channel by ID. Clears active channel if the deleted one was active.
     */
    fun deleteChannel(id: String) {
        val channel = _uiState.value.channels.find { it.id == id }
        val updatedChannels = _uiState.value.channels.filter { it.id != id }
        secureStorage.saveChannels(updatedChannels)

        val newActiveId = if (_uiState.value.activeChannelId == id) {
            secureStorage.saveActiveChannelId("")
            null
        } else {
            _uiState.value.activeChannelId
        }

        _uiState.update { it.copy(
            channels = updatedChannels,
            activeChannelId = newActiveId,
            globalMessage = channel?.let { "渠道 \"${it.name}\" 已删除" }
        )}
    }

    // ── Model Fetching ────────────────────────────

    /**
     * Fetch available models for a specific channel via ModelFetcher.
     * Updates both the fetch state and the channel's fetchedModels list.
     */
    fun fetchModelsForChannel(channelId: String) {
        val channel = _uiState.value.channels.find { it.id == channelId } ?: return
        _uiState.update { it.copy(
            modelFetchStates = it.modelFetchStates + (channelId to FetchState.Fetching)
        )}

        viewModelScope.launch {
            when (val result = modelFetcher.fetchModels(channel)) {
                is Result.Success -> {
                    val models = result.data
                    val updatedChannel = channel.copy(
                        fetchedModels = models,
                        fetchedAt = System.currentTimeMillis()
                    )
                    val updatedChannels = _uiState.value.channels.map {
                        if (it.id == channelId) updatedChannel else it
                    }
                    secureStorage.saveChannels(updatedChannels)
                    _uiState.update { it.copy(
                        channels = updatedChannels,
                        modelFetchStates = it.modelFetchStates + (channelId to FetchState.Success(models))
                    )}
                }
                is Result.Failure -> {
                    _uiState.update { it.copy(
                        modelFetchStates = it.modelFetchStates + (
                            channelId to FetchState.Error(result.exception.userMessage)
                        ),
                        globalMessage = "获取模型失败: ${result.exception.userMessage}"
                    )}
                }
            }
        }
    }

    // ── Pipeline Config ───────────────────────────

    /**
     * Update batch size (texts per API call).
     */
    fun onBatchSizeChange(size: Int) {
        _uiState.update { it.copy(batchSize = size.coerceIn(1, 200)) }
    }

    /**
     * Update concurrency (parallel batches).
     */
    fun onConcurrencyChange(conc: Int) {
        _uiState.update { it.copy(concurrency = conc.coerceIn(1, 10)) }
    }

    // ── Global Message ────────────────────────────

    /**
     * Clear the global snackbar message after display.
     */
    fun clearMessage() {
        _uiState.update { it.copy(globalMessage = null) }
    }
}

// ── UI State ──────────────────────────────────────

/**
 * UI state for the channel-based settings screen.
 */
data class SettingsUiState(
    /** All configured LLM channels. */
    val channels: List<ChannelConfig> = emptyList(),
    /** Currently active channel ID (for translation). */
    val activeChannelId: String? = null,
    /** Currently active model ID (for translation). */
    val activeModelId: String = "",
    /** Whether the "add channel" dialog is visible. */
    val isAddingChannel: Boolean = false,
    /** Channel ID currently being edited (null if not editing). */
    val editingChannelId: String? = null,
    /** Form state for add/edit channel dialog. */
    val newChannelForm: ChannelFormState = ChannelFormState(),
    /** Model fetch states keyed by channel ID. */
    val modelFetchStates: Map<String, FetchState> = emptyMap(),
    /** Translation pipeline: texts per API call. */
    val batchSize: Int = 50,
    /** Translation pipeline: parallel API calls. */
    val concurrency: Int = 1,
    /** Global Snackbar message (null = no message). */
    val globalMessage: String? = null
)

/**
 * Form state for adding/editing a channel.
 */
data class ChannelFormState(
    val name: String = "",
    val type: ChannelType = ChannelType.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyVisible: Boolean = false,
    val fetchingModels: Boolean = false,
    val fetchedCount: Int = 0
)

/**
 * Model fetch progress state for a specific channel.
 */
sealed class FetchState {
    /** No fetch has been requested yet. */
    object Idle : FetchState()
    /** Currently fetching models from the API. */
    object Fetching : FetchState()
    /** Models fetched successfully. */
    data class Success(val models: List<FetchedModel>) : FetchState()
    /** Fetch failed with error message. */
    data class Error(val message: String) : FetchState()
}
