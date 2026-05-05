package com.mtt.app.ui.translation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

/**
 * ViewModel for the translation screen.
 *
 * Manages [TranslationUiState], [TranslationProgress], and [TranslationMode]
 * as [StateFlow]s consumed by the Compose UI.  Delegates heavy lifting to
 * [TranslateTextsUseCase] which orchestrates cache lookup and LLM execution.
 *
 * ### Lifecycle
 * 1. **[onFileSelected]** — load source texts from a file [Uri]
 * 2. **[onChangeMode]** — pick translate / polish / proofread
 * 3. **[onStartTranslation]** — begin / restart the pipeline
 * 4. **[onPauseTranslation]** / **[onResumeTranslation]** — pause & resume
 * 5. **[onExportResult]** — write translated texts to a file [Uri]
 */
@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val translateTexts: TranslateTextsUseCase,
    private val secureStorage: SecureStorage,
    private val glossaryDao: com.mtt.app.data.local.dao.GlossaryDao,
    private val sourceTextRepository: SourceTextRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Public state flows ────────────────────────

    private val _uiState = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(TranslationProgress.initial())
    val progress: StateFlow<TranslationProgress> = _progress.asStateFlow()

    private val _currentMode = MutableStateFlow(TranslationMode.TRANSLATE)
    val currentMode: StateFlow<TranslationMode> = _currentMode.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _prohibitionCount = MutableStateFlow(0)
    val prohibitionCount: StateFlow<Int> = _prohibitionCount.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    init {
        // Note: loadGlossaryEntries() and loadProhibitionCount() are called via viewModelScope.launch()
        // which requires a properly initialized coroutine context. In Hilt-injected ViewModels,
        // viewModelScope is automatically initialized. In unit tests that manually construct
        // the ViewModel, these must be invoked manually after construction.
        loadCustomModelsFromStorage()
        loadModelFromSettings()
    }

    /**
     * Load custom models from SecureStorage and register them in ModelRegistry.
     */
    private fun loadCustomModelsFromStorage() {
        try {
            val json = secureStorage.getCustomModels()
            if (json.isNullOrBlank()) return

            val models = mutableListOf<ModelInfo>()
            val jsonArray = JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val modelId = obj.getString("modelId")
                val displayName = obj.optString("displayName", modelId)
                val contextWindow = obj.optInt("contextWindow", 128000)
                val providerStr = obj.optString("provider", "openai")

                val provider = when (providerStr.lowercase()) {
                    "anthropic" -> {
                        val key = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC) ?: ""
                        LlmProvider.Anthropic(key)
                    }
                    else -> {
                        val key = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI) ?: ""
                        LlmProvider.OpenAI(key)
                    }
                }

                models.add(
                    ModelInfo(
                        modelId = modelId,
                        displayName = displayName,
                        contextWindow = contextWindow,
                        provider = provider,
                        isCustom = true
                    )
                )
            }

            ModelRegistry.initCustomModels(models)
        } catch (_: Exception) {
            // Silently ignore parsing errors - custom models simply won't be available
        }
    }

    /**
     * Reload settings from SecureStorage (model selection, languages).
     * Call this when returning from Settings screen to pick up changes.
     */
    fun reloadSettings() {
        loadCustomModelsFromStorage()
        loadModelFromSettings()
    }

    /**
     * Load glossary entries from database.
     * Called automatically in production (Hilt); must be called manually in unit tests.
     */
    private fun loadGlossaryEntries() {
        try {
            // Check if viewModelScope is properly initialized by attempting to access it
            @Suppress("UNUSED_VARIABLE")
            val dummy = viewModelScope.hashCode()
            viewModelScope.launch(ioDispatcher) {
                glossaryEntries = glossaryDao.getByProjectId("default_project")
            }
        } catch (_: Exception) {
            // viewModelScope not initialized (unit test context) - skip loading
        }
    }

    /**
     * Load the count of prohibition entries (entries with empty targetTerm).
     * Called automatically in production (Hilt); must be called manually in unit tests.
     */
    private fun loadProhibitionCount() {
        try {
            @Suppress("UNUSED_VARIABLE")
            val dummy = viewModelScope.hashCode()
            viewModelScope.launch(ioDispatcher) {
                val entries = glossaryDao.getByProjectId("default_project")
                _prohibitionCount.value = entries.count { it.targetTerm.isEmpty() }
            }
        } catch (_: Exception) {
            // viewModelScope not initialized (unit test context) - skip loading
        }
    }

    /**
     * Refresh prohibition count when glossary data changes.
     * Call this when returning from GlossaryScreen.
     */
    fun refreshGlossary() {
        loadGlossaryEntries()
        loadProhibitionCount()
    }

    /** Texts loaded from the source file, ready for translation. */
    private var sourceTexts: List<String> = emptyList()

    /** Original key-value map from the MTool JSON (key=source, value=translation). */
    private var sourceTextMap: Map<String, String> = emptyMap()

    /** Accumulated translation results (indexed 1:1 with [sourceTexts]). */
    private var translatedResults: List<String> = emptyList()

    /** Currently running translation coroutine (null when idle). */
    private var translationJob: Job? = null

    /** Override-able dispatcher for file I/O. Tests can swap this for [kotlinx.coroutines.test.TestDispatcher]. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // ── Configurable translation parameters ───────
    // Loaded from SecureStorage (configured in Settings screen)

    /** Current source language (e.g., "日语", "英语"). Read by TranslationScreen to initialize dropdown. */
    var sourceLang: String = secureStorage.getApiKey(SecureStorage.KEY_SOURCE_LANG) ?: "日语"
        private set

    /** Current target language (e.g., "中文", "英语"). Read by TranslationScreen to initialize dropdown. */
    var targetLang: String = secureStorage.getApiKey(SecureStorage.KEY_TARGET_LANG) ?: "中文"
        private set
    private var temperature: Float = 0.3f
    private var maxTokens: Int = 4096
    private var glossaryEntries: List<GlossaryEntryEntity> = emptyList()
    private var modelInfo: ModelInfo = ModelRegistry.defaultOpenAiModel

    /**
     * Load the saved model configuration from SecureStorage.
     * Falls back to ModelRegistry defaults if nothing is saved.
     * Updates [_currentModel] StateFlow with the loaded model.
     */
    private fun loadModelFromSettings() {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC)

        modelInfo = if (anthropicKey?.isNotBlank() == true && openAiKey?.isNotBlank() != true) {
            // Anthropic is configured (has key, OpenAI doesn't)
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_ANTHROPIC_MODEL)
                ?: ModelRegistry.defaultAnthropicModel.modelId
            ModelRegistry.getById(modelId) ?: ModelInfo(
                modelId = modelId,
                displayName = modelId,
                contextWindow = 200000,
                provider = LlmProvider.Anthropic(anthropicKey)
            )
        } else {
            // Default to OpenAI (or both configured, use OpenAI)
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_OPENAI_MODEL)
                ?: ModelRegistry.defaultOpenAiModel.modelId
            ModelRegistry.getById(modelId) ?: ModelInfo(
                modelId = modelId,
                displayName = modelId,
                contextWindow = 128000,
                provider = LlmProvider.OpenAI(openAiKey ?: "")
            )
        }
        _currentModel.value = modelInfo
    }

    // ── Language selection ────────────────────────

    fun updateSourceLang(lang: String) {
        sourceLang = lang
    }

    fun updateTargetLang(lang: String) {
        targetLang = lang
    }

    // ── Events ────────────────────────────────────

    /**
     * Read the file at [uri] and extract texts from MTool JSON (key=source, value=translation).
     *
     * Emits [TranslationUiState.Loading] while reading, then transitions to
     * [TranslationUiState.Idle] on success or [TranslationUiState.Error] on failure.
     */
    fun onFileSelected(uri: Uri, fileName: String? = null) {
        _uiState.value = TranslationUiState.Loading("Reading file...")
        if (fileName != null) _selectedFileName.value = fileName

        viewModelScope.launch(ioDispatcher) {
            try {
                val content = context.contentResolver
                    .openInputStream(uri)
                    ?.use { it.bufferedReader().readText() }
                    ?: throw IOException("Cannot open file")

                // Parse MTool JSON format: { "source": "source", ... }
                // where key=source text, value=current translation (initially same as key)
                val json = JSONObject(content.trim())
                val keys = json.keys()
                val map = LinkedHashMap<String, String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key, key)
                    map[key] = value
                }

                sourceTextMap = map
                sourceTexts = map.values.toList()

                // Store source texts in repository for cross-ViewModel access
                sourceTextRepository.setSourceTexts(map)

                translatedResults = emptyList()

                _progress.update {
                    it.copy(
                        totalItems = sourceTexts.size,
                        completedItems = 0,
                        status = "Ready"
                    )
                }
                _uiState.value = TranslationUiState.Idle
            } catch (e: Exception) {
                _uiState.value = TranslationUiState.Error(
                    "Failed to read file: ${e.message}"
                )
            }
        }
    }

    /**
     * Start (or restart) the translation pipeline.
     *
     * Requires [sourceTexts] to be non-empty.  Builds a [TranslationConfig] from
     * the current internal parameters, then collects [BatchResult] events from
     * [TranslateTextsUseCase] and maps them to [uiState] / [progress].
     */
    fun onStartTranslation() {
        if (sourceTexts.isEmpty()) {
            _uiState.value = TranslationUiState.Error("No texts to translate")
            return
        }

        // Cancel any in-flight translation
        translationJob?.cancel()

        val config = TranslationConfig(
            mode = _currentMode.value,
            model = _currentModel.value ?: ModelRegistry.defaultOpenAiModel,
            sourceLang = sourceLang,
            targetLang = targetLang,
            glossaryEntries = glossaryEntries,
            temperature = temperature,
            maxTokens = maxTokens
        )

        _uiState.value = TranslationUiState.Translating(_progress.value)

        translationJob = viewModelScope.launch {
            translateTexts(sourceTexts, config).collect { result ->
                handleBatchResult(result)
            }
        }
    }

    /** Cancel the current translation job and return to idle. */
    fun onPauseTranslation() {
        translationJob?.cancel()
        translationJob = null
        _uiState.value = TranslationUiState.Idle
    }

    /**
     * Resume translation from the current state.
     *
     * Currently restarts the full pipeline (the executor processes batches
     * atomically; partial progress is tracked but cannot be resumed mid-batch).
     * Cache hits from a previous partial run will short-circuit repeated work.
     */
    fun onResumeTranslation() {
        onStartTranslation()
    }

    /** Switch the active [TranslationMode] (translate / polish / proofread). */
    fun onChangeMode(mode: TranslationMode) {
        _currentMode.value = mode
    }

    /**
     * Write [translatedResults] to the file at [uri] as UTF-8 text.
     *
     * Each translated item is written on its own line.  Emits
     * [TranslationUiState.Completed] on success.
     */
    fun onExportResult(uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            try {
                // Rebuild MTool JSON: map original keys to translated values
                val keys = sourceTextMap.keys.toList()
                val outputJson = JSONObject()
                for (i in keys.indices) {
                    val translated = translatedResults.getOrElse(i) { sourceTextMap[keys[i]] ?: "" }
                    outputJson.put(keys[i], translated)
                }
                val content = outputJson.toString(2) // pretty-print with 2-space indent

                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Cannot open output file")

                _uiState.value = TranslationUiState.Completed
            } catch (e: Exception) {
                _uiState.value = TranslationUiState.Error(
                    "Failed to export: ${e.message}"
                )
            }
        }
    }

    // ── Internal helpers ──────────────────────────

    /** Map a [BatchResult] event to UI state and progress. */
    private fun handleBatchResult(result: BatchResult) {
        when (result) {
            is BatchResult.Started -> {
                _progress.update {
                    it.copy(
                        totalItems = result.size,
                        completedItems = 0,
                        currentBatch = result.batchIndex,
                        totalBatches = 1,
                        status = "Translation started"
                    )
                }
                _uiState.value = TranslationUiState.Translating(_progress.value)
            }

            is BatchResult.Progress -> {
                _progress.update {
                    it.copy(
                        completedItems = result.completed,
                        totalItems = result.total,
                        status = result.stage
                    )
                }
                _uiState.value = TranslationUiState.Translating(_progress.value)
            }

            is BatchResult.Retrying -> {
                _progress.update {
                    it.copy(status = "Retrying: ${result.reason}")
                }
                _uiState.value = TranslationUiState.Translating(_progress.value)
            }

            is BatchResult.Success -> {
                translatedResults = result.items
                _progress.update {
                    it.copy(
                        completedItems = translatedResults.size,
                        totalItems = translatedResults.size,
                        status = "Complete"
                    )
                }
                _uiState.value = TranslationUiState.Completed
            }

            is BatchResult.Failure -> {
                _uiState.value = TranslationUiState.Error(
                    result.error.message ?: "Translation failed"
                )
            }
        }
    }
}
