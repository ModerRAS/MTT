package com.mtt.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.model.ChannelConfig
import com.mtt.app.data.model.ChannelType
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.FailedItem
import com.mtt.app.data.model.ExtractionJobEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationJobEntity
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.util.LanguageDetector
import com.mtt.app.domain.usecase.ExtractTermsUseCase
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import com.mtt.app.service.TranslationService
import com.mtt.app.ui.glossary.ExtractionProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Task types visible on the home screen.
 */
enum class TaskType {
    TRANSLATE,
    POLISH,
    PROOFREAD,
    EXTRACT
}

/**
 * Screen-level state for the home screen.
 */
sealed class ScreenState {
    data object Idle : ScreenState()
    data class Loading(val message: String) : ScreenState()
    data class Translating(val progress: TranslationProgress) : ScreenState()
    data object Completed : ScreenState()
    data class Error(val message: String) : ScreenState()
    data class Resumable(
        val jobId: String,
        val totalItems: Int,
        val completedItems: Int,
        val sourceFileName: String?
    ) : ScreenState()
}

/**
 * Combined UI state for the home screen.
 */
data class HomeUiState(
    val taskType: TaskType = TaskType.TRANSLATE,
    val screenState: ScreenState = ScreenState.Idle,
    val selectedFileName: String? = null,
    val selectedFileUri: String? = null,
    val sourceLang: String = "中文",
    val targetLang: String = "英语",
    val currentModelName: String = "",
    val prohibitionCount: Int = 0,
    val progress: TranslationProgress = TranslationProgress.initial(),
    val extractedTerms: List<ExtractedTerm> = emptyList(),
    val showExtractionReview: Boolean = false,
    val isExtracting: Boolean = false,
    val extractionProgress: ExtractionProgress = ExtractionProgress(0, 0),
    val extractionMessage: String? = null,
    val shouldShowTerminalDialog: Boolean = false,
    val failedItems: List<FailedItem> = emptyList(),
    val isRetrying: Boolean = false
)

/**
 * ViewModel for the home screen — the main dashboard of the app.
 *
 * Manages [HomeUiState] as a single [StateFlow] consumed by the Compose UI.
 * Handles file loading, translation, glossary extraction, and progress tracking.
 *
 * ### Lifecycle
 * 1. **[onFileSelected]** — load source texts from a JSON file
 * 2. **[onChangeTaskType]** — pick translate / polish / proofread / extract
 * 3. **[onStartTask]** — begin translation or extraction based on taskType
 * 4. **[onPauseTranslation]** / **[onResumeTranslation]** — pause & resume
 * 5. **[onExportResult]** — write translated texts to a file
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val translateTexts: TranslateTextsUseCase,
    private val secureStorage: SecureStorage,
    private val glossaryDao: GlossaryDao,
    private val sourceTextRepository: SourceTextRepository,
    private val extractTermsUseCase: ExtractTermsUseCase,
    private val translationJobDao: TranslationJobDao,
    private val extractionJobDao: ExtractionJobDao,
    private val cacheManager: CacheManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── Internal state ────────────────────────────

    /** Texts loaded from the source file, ready for translation. */
    private var sourceTexts: List<String> = emptyList()

    /** Original key-value map from the MTool JSON (key=source, value=translation). */
    private var sourceTextMap: Map<String, String> = emptyMap()

    /** Accumulated translation results (indexed 1:1 with [sourceTexts]). */
    private var translatedResults: List<String> = emptyList()

    /** Currently running translation coroutine (null when idle). */
    private var translationJob: Job? = null

    /** Override-able dispatcher for file I/O. Tests can swap this. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** When true, translation runs through [TranslationService] foreground service. */
    internal var useService: Boolean = true

    /** When set, [startTranslationViaService] cancels this job before creating a new one. */
    private var pendingCancelJobId: String? = null

    /** Local retry indices that still failed during the current retry run. */
    private var currentRetryFailedLocalIndices: Set<Int> = emptySet()

    // ── Configurable parameters ────────────────────

    private var temperature: Float = 0.3f
    private var maxTokens: Int = 16384
    private var glossaryEntries: List<GlossaryEntryEntity> = emptyList()
    private var modelInfo: ModelInfo = ModelRegistry.defaultOpenAiModel

    init {
        loadCustomModelsFromStorage()
        loadActiveConfig()
        loadGlossaryEntries()
        loadProhibitionCount()
        tryAutoStartTranslation()
        checkForIncompleteTranslationJob()
        checkForIncompleteExtractionJob()
    }

    // ── Model / Channel Loading ───────────────────

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
            // Silently ignore parsing errors
        }
    }

    /**
     * Load active channel/model from SecureStorage.
     * Tries channel-based config first, falls back to legacy settings.
     */
    private fun loadActiveConfig() {
        val activeChannelId = secureStorage.loadActiveChannelId()
        val channels = secureStorage.loadChannels()
        val channel = channels.find { it.id == activeChannelId }

        if (channel != null) {
            loadModelFromChannel(channel)
        } else {
            loadModelFromSettings()
        }
    }

    /**
     * Load model info from a [ChannelConfig].
     */
    private fun loadModelFromChannel(channel: ChannelConfig) {
        val activeModelId = secureStorage.loadActiveModelId()
        val model = channel.fetchedModels.find { it.modelId == activeModelId }
        val displayName = model?.displayName
            ?: activeModelId
            ?: "${channel.name} (${channel.type.name})"

        val provider = when (channel.type) {
            ChannelType.OPENAI -> LlmProvider.OpenAI(channel.apiKey, channel.baseUrl)
            ChannelType.ANTHROPIC -> LlmProvider.Anthropic(channel.apiKey, channel.baseUrl)
        }

        modelInfo = ModelInfo(
            modelId = model?.modelId ?: (activeModelId ?: "unknown"),
            displayName = displayName,
            contextWindow = model?.contextWindow ?: 128000,
            provider = provider
        )

        _uiState.update { it.copy(currentModelName = displayName) }
    }

    /**
     * Load the saved model configuration from SecureStorage (legacy fallback).
     */
    private fun loadModelFromSettings() {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC)
        val openAiBaseUrl = secureStorage.getValue(SecureStorage.KEY_OPENAI_BASE_URL)
            ?: LlmProvider.OpenAI("", "").baseUrl
        val anthropicBaseUrl = secureStorage.getValue(SecureStorage.KEY_ANTHROPIC_BASE_URL)
            ?: LlmProvider.Anthropic("", "").baseUrl

        modelInfo = if (anthropicKey?.isNotBlank() == true && openAiKey?.isNotBlank() != true) {
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_ANTHROPIC_MODEL)
                ?: ModelRegistry.defaultAnthropicModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                baseModel.copy(provider = LlmProvider.Anthropic(anthropicKey, anthropicBaseUrl))
            } else {
                ModelInfo(
                    modelId = modelId,
                    displayName = modelId,
                    contextWindow = 200000,
                    provider = LlmProvider.Anthropic(anthropicKey, anthropicBaseUrl)
                )
            }
        } else {
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_OPENAI_MODEL)
                ?: ModelRegistry.defaultOpenAiModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                baseModel.copy(provider = LlmProvider.OpenAI(openAiKey ?: "", openAiBaseUrl))
            } else {
                ModelInfo(
                    modelId = modelId,
                    displayName = modelId,
                    contextWindow = 128000,
                    provider = LlmProvider.OpenAI(openAiKey ?: "", openAiBaseUrl)
                )
            }
        }

        val displayName = modelInfo.displayName
        _uiState.update { it.copy(currentModelName = displayName) }
    }

    /**
     * Reload settings from SecureStorage (model selection, languages).
     * Call when returning from Settings screen.
     */
    fun reloadSettings() {
        loadCustomModelsFromStorage()
        loadActiveConfig()
    }

    // ── Glossary loading ──────────────────────────

    private fun loadGlossaryEntries() {
        try {
            @Suppress("UNUSED_VARIABLE")
            val dummy = viewModelScope.hashCode()
            viewModelScope.launch(ioDispatcher) {
                glossaryEntries = glossaryDao.getByProjectId("default_project")
            }
        } catch (_: Exception) {
            // viewModelScope not initialized (unit test context)
        }
    }

    private fun loadProhibitionCount() {
        try {
            @Suppress("UNUSED_VARIABLE")
            val dummy = viewModelScope.hashCode()
            viewModelScope.launch(ioDispatcher) {
                val entries = glossaryDao.getByProjectId("default_project")
                _uiState.update { it.copy(prohibitionCount = entries.count { e -> e.targetTerm.isEmpty() }) }
            }
        } catch (_: Exception) {
            // viewModelScope not initialized (unit test context)
        }
    }

    fun refreshGlossary() {
        loadGlossaryEntries()
        loadProhibitionCount()
    }

    // ── Auto-start (debug) ────────────────────────

    private fun tryAutoStartTranslation() {
        AppLogger.d(TAG, "tryAutoStartTranslation called, pendingAutoLoad=${pendingAutoLoad != null}")
        val autoData = pendingAutoLoad ?: return
        pendingAutoLoad = null

        val pendingTexts = autoData.sourceTexts
        val pendingMap = autoData.sourceTextMap
        val pendingName = autoData.fileName

        viewModelScope.launch(Dispatchers.Default) {
            try {
                sourceTexts = pendingTexts
                sourceTextMap = pendingMap
                AppLogger.d(TAG, "Setting selectedFileName=$pendingName, totalItems=${pendingTexts.size}")
                val detectedLanguages = detectLanguagesFor(sourceTexts)
                _uiState.update {
                    it.copy(
                        selectedFileName = pendingName,
                        sourceLang = detectedLanguages.first,
                        targetLang = detectedLanguages.second,
                        progress = it.progress.copy(
                            totalItems = sourceTexts.size,
                            completedItems = 0,
                            status = "Ready"
                        )
                    )
                }
                sourceTextRepository.setSourceTexts(pendingMap)
                AppLogger.d(TAG, "tryAutoStartTranslation completed")
            } catch (e: Exception) {
                AppLogger.e(TAG, "tryAutoStartTranslation failed", e)
                _uiState.update { it.copy(screenState = ScreenState.Error("自动翻译启动失败: ${e.message}")) }
            }
        }
    }

    // ── Language selection ─────────────────────────

    fun updateSourceLang(lang: String) {
        _uiState.update { it.copy(sourceLang = lang) }
    }

    fun updateTargetLang(lang: String) {
        _uiState.update { it.copy(targetLang = lang) }
    }

    // ── Task type ──────────────────────────────────

    fun onChangeTaskType(type: TaskType) {
        _uiState.update { it.copy(taskType = type) }
    }

    // ── File selection ─────────────────────────────

    fun onFileSelected(uri: Uri, fileName: String? = null) {
        _uiState.update { it.copy(screenState = ScreenState.Loading("Reading file..."), selectedFileUri = uri.toString()) }
        if (fileName != null) _uiState.update { it.copy(selectedFileName = fileName) }

        viewModelScope.launch(ioDispatcher) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open file")

                val map = LinkedHashMap<String, String>()
                inputStream.use { stream ->
                    val jsonReader = com.mtt.app.data.io.StreamingJsonReader(stream)
                    jsonReader.use { reader ->
                        reader.readEntries { key, value -> map[key] = value }
                    }
                }

                sourceTextMap = map
                sourceTexts = map.values.toList()
                sourceTextRepository.setSourceTexts(map)
                translatedResults = emptyList()

                val detectedLanguages = detectLanguagesFor(sourceTexts)

                _uiState.update {
                    it.copy(
                        screenState = ScreenState.Idle,
                        sourceLang = detectedLanguages.first,
                        targetLang = detectedLanguages.second,
                        progress = it.progress.copy(
                            totalItems = sourceTexts.size,
                            completedItems = 0,
                            status = "Ready"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(screenState = ScreenState.Error("Failed to read file: ${e.message}")) }
            }
        }
    }

    // ── Task execution ─────────────────────────────

    /**
     * Start the current task based on [TaskType].
     * For TRANSLATE/POLISH/PROOFREAD: build config and start translation service.
     * For EXTRACT: run glossary extraction.
     */
    fun onStartTask() {
        when (_uiState.value.taskType) {
            TaskType.EXTRACT -> extractTerms()
            else -> {
                if (sourceTexts.isEmpty()) {
                    _uiState.update { it.copy(screenState = ScreenState.Error("No texts to translate")) }
                    return
                }
                if (useService) {
                    startTranslationViaService()
                } else {
                    startTranslationDirectly()
                }
            }
        }
    }

    private fun taskTypeToMode(type: TaskType): TranslationMode = when (type) {
        TaskType.TRANSLATE -> TranslationMode.TRANSLATE
        TaskType.POLISH -> TranslationMode.POLISH
        TaskType.PROOFREAD -> TranslationMode.PROOFREAD
        TaskType.EXTRACT -> TranslationMode.TRANSLATE // fallback
    }

    private fun detectLanguagesFor(texts: List<String>): Pair<String, String> {
        val sourceLang = LanguageDetector.detect(texts)
        return sourceLang to LanguageDetector.defaultTargetFor(sourceLang)
    }

    // ── Direct translation (test path) ─────────────

    internal fun startTranslationDirectly() {
        translationJob?.cancel()

        val config = buildConfig()
        _uiState.update {
                    it.copy(
                        screenState = ScreenState.Translating(it.progress),
                        failedItems = emptyList(),
                        shouldShowTerminalDialog = false
                    )
                }

        translationJob = viewModelScope.launch {
            translateTexts(sourceTexts, config)
                .flowOn(ioDispatcher)
                .collect { result -> handleBatchResult(result) }
        }
    }

    // ── Service translation ─────────────────────────

    private fun startTranslationViaService() {
        translationJob?.cancel()

        val cancelOldId = pendingCancelJobId
        pendingCancelJobId = null

        val config = buildConfig()
        val fileUri = _uiState.value.selectedFileUri ?: ""
        val fileName = _uiState.value.selectedFileName

        val jobId = UUID.randomUUID().toString()

        if (cancelOldId != null) {
            viewModelScope.launch {
                try {
                    translationJobDao.updateProgress(
                        jobId = cancelOldId,
                        status = TranslationJobEntity.STATUS_CANCELLED,
                        completedItems = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                } catch (_: Exception) { }
            }
        }

        writeSourceTextsToFile(jobId, sourceTexts, sourceTextMap)
        val job = TranslationJobEntity(
            jobId = jobId,
            status = TranslationJobEntity.STATUS_IN_PROGRESS,
            totalItems = sourceTexts.size,
            completedItems = 0,
            sourceFileUri = fileUri,
            sourceFileName = fileName,
            configJson = TranslationJobEntity.serializeConfig(config),
            sourceTextsJson = "",
            sourceTextMapJson = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        runBlocking(Dispatchers.IO) {
            translationJobDao.insert(job)
        }

        TranslationService.pendingTexts = sourceTexts.toList()
        TranslationService.pendingConfig = config
        TranslationService.pendingJobId = jobId
        TranslationService.pendingFailedItems = emptyList()

        val intent = Intent(context, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)

        _uiState.update {
                    it.copy(
                        screenState = ScreenState.Translating(TranslationProgress.initial()),
                        failedItems = emptyList(),
                        shouldShowTerminalDialog = false
                    )
                }
        collectServiceProgress()
    }

    private fun collectServiceProgress() {
        translationJob = viewModelScope.launch {
            TranslationService.serviceProgress.collectLatest { progress ->
                _uiState.update { it.copy(progress = progress) }
                when (progress.status) {
                    "翻译完成" -> {
                        loadResultsFromCache()
                        val failedItems = TranslationService.pendingFailedItems
                        _uiState.update {
                            it.copy(
                                screenState = ScreenState.Completed,
                                failedItems = failedItems,
                                shouldShowTerminalDialog = failedItems.isNotEmpty()
                            )
                        }
                    }
                    "翻译失败" -> {
                        _uiState.update { it.copy(screenState = ScreenState.Error("翻译失败")) }
                    }
                    "已取消" -> {
                        _uiState.update {
                            it.copy(
                                screenState = ScreenState.Idle,
                                progress = TranslationProgress.initial()
                            )
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(screenState = ScreenState.Translating(progress)) }
                    }
                }
            }
        }
    }

    private fun collectServiceProgressSync() {
        viewModelScope.launch {
            TranslationService.serviceProgress.collectLatest { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    // ── Source text file storage ───────────────────

    private fun writeSourceTextsToFile(jobId: String, texts: List<String>, textMap: Map<String, String>) {
        val dir = java.io.File(context.cacheDir, "translation_sources")
        dir.mkdirs()
        val file = java.io.File(dir, "$jobId.json")
        JSONObject().apply {
            put("texts", JSONArray(texts))
            put("map", JSONObject(textMap.toMap()))
        }.let { file.writeText(it.toString(), Charsets.UTF_8) }
    }

    private fun readSourceTextsFromFile(jobId: String): Pair<List<String>, Map<String, String>>? {
        val file = java.io.File(context.cacheDir, "translation_sources/$jobId.json")
        if (!file.exists()) return null
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        val textsArr = obj.getJSONArray("texts")
        val texts = (0 until textsArr.length()).map { textsArr.optString(it, "") }
        val mapObj = obj.getJSONObject("map")
        val map = LinkedHashMap<String, String>()
        mapObj.keys().forEach { key -> map[key] = mapObj.optString(key, "") }
        return Pair(texts, map)
    }

    // ── Config builder ─────────────────────────────

    private fun buildConfig(): TranslationConfig {
        val safeMode = taskTypeToMode(_uiState.value.taskType)
        val safeModel = modelInfo
        val safeSourceLang = _uiState.value.sourceLang
        val safeTargetLang = _uiState.value.targetLang
        val safeGlossary = glossaryEntries

        val savedBatchSize = secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE)?.toIntOrNull() ?: 50
        val savedConcurrency = secureStorage.getValue(SecureStorage.KEY_CONCURRENCY)?.toIntOrNull() ?: 1

        return TranslationConfig(
            mode = safeMode,
            model = safeModel,
            sourceLang = safeSourceLang,
            targetLang = safeTargetLang,
            glossaryEntries = safeGlossary,
            temperature = temperature,
            maxTokens = maxTokens,
            batchSize = savedBatchSize.coerceIn(1, 200),
            concurrency = savedConcurrency.coerceIn(1, 10)
        )
    }

    private suspend fun loadResultsFromCache() {
        try {
            val projectId = _uiState.value.selectedFileName ?: CacheManager.DEFAULT_PROJECT_ID
            val cacheMap = cacheManager.exportToJson(projectId)
            translatedResults = sourceTexts.map { cacheMap[it] ?: it }
        } catch (_: Exception) {
            translatedResults = sourceTexts.toList()
        }
    }

    // ── Batch result handling ──────────────────────

    private fun handleBatchResult(result: BatchResult) {
        when (result) {
            is BatchResult.Started -> {
                _uiState.update {
                    it.copy(
                        progress = it.progress.copy(
                            totalItems = result.size,
                            completedItems = 0,
                            currentBatch = result.batchIndex,
                            totalBatches = 1,
                            status = "Translation started"
                        ),
                        screenState = ScreenState.Translating(it.progress)
                    )
                }
            }
            is BatchResult.Progress -> {
                _uiState.update {
                    it.copy(
                        progress = it.progress.copy(
                            completedItems = result.completed,
                            totalItems = result.total,
                            status = result.stage
                        ),
                        screenState = ScreenState.Translating(it.progress)
                    )
                }
            }
            is BatchResult.Retrying -> {
                _uiState.update {
                    it.copy(
                        progress = it.progress.copy(status = "Retrying: ${result.reason}"),
                        screenState = ScreenState.Translating(it.progress)
                    )
                }
            }
            is BatchResult.Success -> {
                translatedResults = result.items
                _uiState.update {
                    it.copy(
                        progress = it.progress.copy(
                            completedItems = translatedResults.size,
                            totalItems = translatedResults.size,
                            status = "Complete",
                            totalInputTokens = it.progress.totalInputTokens + result.inputTokens,
                            totalOutputTokens = it.progress.totalOutputTokens + result.outputTokens,
                            totalCacheTokens = it.progress.totalCacheTokens + result.cacheTokens
                        ),
                        screenState = ScreenState.Completed
                    )
                }
            }
            is BatchResult.Failure -> {
                _uiState.update {
                    it.copy(screenState = ScreenState.Error(result.error.message ?: "Translation failed"))
                }
            }
            is BatchResult.VerificationComplete -> {
                AppLogger.i(TAG, "Verification complete: ${result.failedCount}/${result.totalItems} failed")
            }
            is BatchResult.RetryProgress -> {
                AppLogger.d(TAG, "Retry round ${result.round}: ${result.completed}/${result.total}")
            }
            is BatchResult.RetryComplete -> {
                AppLogger.w(TAG, "Retry complete: ${result.finalFailedItems.size} items permanently failed")
                _uiState.update {
                    it.copy(
                        shouldShowTerminalDialog = result.finalFailedItems.isNotEmpty(),
                        failedItems = result.finalFailedItems
                    )
                }
            }
        }
    }

    // ── Retry/Skip failed items ─────────────────────

    /**
     * Retry all failed items using CURRENT model settings (from SecureStorage).
     */
    fun retryAllFailed() {
        val items = _uiState.value.failedItems
        if (items.isEmpty()) return

        // Get source texts for failed items
        val textsToRetry = items.mapNotNull { item ->
            sourceTexts.getOrNull(item.globalIndex)
        }
        if (textsToRetry.isEmpty()) return

        viewModelScope.launch {
            try {
                // Reload current settings (model may have changed)
                loadActiveConfig()
                reloadGlossaryEntriesSync()

                val config = buildConfig()
                currentRetryFailedLocalIndices = emptySet()
                _uiState.update {
                    it.copy(screenState = ScreenState.Translating(it.progress), isRetrying = true)
                }

                translateTexts(textsToRetry, config)
                    .flowOn(ioDispatcher)
                    .collect { result -> handleRetryResult(result, items) }
            } catch (e: Exception) {
                AppLogger.e(TAG, "retryAllFailed failed", e)
                _uiState.update {
                    it.copy(screenState = ScreenState.Error("重试失败: ${e.message}"))
                }
            }
        }
    }

    /**
     * Retry a single failed item by global index.
     */
    fun retrySingleFailed(globalIndex: Int) {
        val sourceText = sourceTexts.getOrNull(globalIndex) ?: return
        val currentFailedItems = _uiState.value.failedItems

        viewModelScope.launch {
            try {
                loadActiveConfig()
                reloadGlossaryEntriesSync()

                val config = buildConfig()
                currentRetryFailedLocalIndices = emptySet()
                _uiState.update {
                    it.copy(screenState = ScreenState.Translating(it.progress), isRetrying = true)
                }

                val singleItemList = listOf(FailedItem(globalIndex = globalIndex, sourceText = sourceText))
                translateTexts(listOf(sourceText), config)
                    .flowOn(ioDispatcher)
                    .collect { result -> handleRetryResult(result, singleItemList) }
            } catch (e: Exception) {
                AppLogger.e(TAG, "retrySingleFailed failed", e)
                _uiState.update {
                    it.copy(screenState = ScreenState.Error("重试失败: ${e.message}"))
                }
            }
        }
    }

    /**
     * Skip a failed item - mark as permanently failed and remove from list.
     */
    fun skipFailed(globalIndex: Int) {
        _uiState.update { state ->
            state.copy(
                failedItems = state.failedItems.filterNot { it.globalIndex == globalIndex }
            )
        }
    }

    private fun handleRetryResult(result: BatchResult, originalItems: List<FailedItem>) {
        val localToGlobal = originalItems.mapIndexed { localIndex, failedItem ->
            localIndex to failedItem.globalIndex
        }.toMap()
        fun remapFailedItems(items: List<FailedItem>): List<FailedItem> =
            items.map { failed ->
                failed.copy(globalIndex = localToGlobal[failed.globalIndex] ?: failed.globalIndex)
            }

        when (result) {
            is BatchResult.VerificationComplete -> {
                currentRetryFailedLocalIndices = remapFailedItems(result.failedItems).map { it.globalIndex }.toSet()
                _uiState.update { state ->
                    state.copy(
                        failedItems = remapFailedItems(result.failedItems),
                        shouldShowTerminalDialog = false
                    )
                }
            }
            is BatchResult.Success -> {
                val stillFailed = currentRetryFailedLocalIndices.mapNotNull { localToGlobal[it] }.toSet()
                // Update translated results
                originalItems.forEachIndexed { idx, item ->
                    if (item.globalIndex in stillFailed) return@forEachIndexed
                    if (idx < result.items.size) {
                        val translated = result.items[idx]
                        val currentResults = translatedResults.toMutableList()
                        if (item.globalIndex < currentResults.size) {
                            currentResults[item.globalIndex] = translated
                        }
                        translatedResults = currentResults
                    }
                }

                // Remove succeeded items from failed list
                _uiState.update { state ->
                    state.copy(
                        failedItems = state.failedItems.filterNot { failed ->
                            originalItems.any { it.globalIndex == failed.globalIndex } &&
                                    failed.globalIndex !in stillFailed
                        },
                        isRetrying = false,
                        screenState = if (state.failedItems.isEmpty()) ScreenState.Completed else state.screenState
                    )
                }
                currentRetryFailedLocalIndices = emptySet()
            }
            is BatchResult.Failure -> {
                _uiState.update {
                    it.copy(screenState = ScreenState.Error("重试失败: ${result.error.message}"), isRetrying = false)
                }
            }
            is BatchResult.RetryComplete -> {
                // Update failed items with new retry state
                val remappedRetryItems = remapFailedItems(result.finalFailedItems)
                currentRetryFailedLocalIndices = remappedRetryItems.map { it.globalIndex }.toSet()
                _uiState.update { state ->
                    val updatedFailed = state.failedItems.map { existing ->
                        val retryItem = remappedRetryItems.find { it.globalIndex == existing.globalIndex }
                        if (retryItem != null) retryItem else existing
                    }
                    state.copy(
                        failedItems = updatedFailed,
                        isRetrying = false,
                        shouldShowTerminalDialog = updatedFailed.isNotEmpty()
                    )
                }
            }
            else -> {
                // Ignore progress/retrying events during retry
            }
        }
    }

    // ── Pause / Resume ─────────────────────────────

    fun onPauseTranslation() {
        translationJob?.cancel()
        translationJob = null
        _uiState.update {
            it.copy(
                screenState = ScreenState.Idle,
                progress = TranslationProgress.initial()
            )
        }

        if (useService) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = TranslationService.ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun onResumeTranslation() {
        onStartTask()
    }

    // ── Resume from job ────────────────────────────

    private fun checkForIncompleteTranslationJob() {
        viewModelScope.launch {
            try {
                val terminalStatuses = listOf(
                    TranslationJobEntity.STATUS_COMPLETED,
                    TranslationJobEntity.STATUS_FAILED,
                    TranslationJobEntity.STATUS_CANCELLED
                )
                val summary = translationJobDao.getLatestIncompleteSummary(terminalStatuses)
                if (summary != null && summary.totalItems > 0) {
                    AppLogger.d(TAG, "checkForIncompleteTranslationJob: FOUND job ${summary.jobId}")
                    _uiState.update {
                        it.copy(screenState = ScreenState.Resumable(
                            jobId = summary.jobId,
                            totalItems = summary.totalItems,
                            completedItems = summary.completedItems,
                            sourceFileName = summary.sourceFileName
                        ))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "checkForIncompleteTranslationJob failed", e)
            }
        }
    }

    private fun checkForIncompleteExtractionJob() {
        viewModelScope.launch {
            try {
                val terminalStatuses = listOf(
                    ExtractionJobEntity.STATUS_COMPLETED,
                    ExtractionJobEntity.STATUS_FAILED
                )
                val incomplete = extractionJobDao.getLatestIncomplete(terminalStatuses)
                if (incomplete != null) {
                    val restoredTexts = ExtractionJobEntity.deserializeTexts(incomplete.sourceTextsJson)
                    if (restoredTexts.isNotEmpty()) {
                        sourceTextRepository.setSourceTexts(restoredTexts)
                    }
                    _uiState.update { it.copy(extractionMessage = "上次术语提取未完成，请重新提取") }
                }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    fun resumeFromJob(jobId: String) {
        viewModelScope.launch {
            try {
                val meta = translationJobDao.getMetadataById(jobId) ?: return@launch

                val fileData = readSourceTextsFromFile(jobId)
                val restoredTexts: List<String>
                val restoredMap: Map<String, String>
                if (fileData != null) {
                    restoredTexts = fileData.first
                    restoredMap = fileData.second
                } else {
                    val textsJson = try { translationJobDao.getSourceTextsJson(jobId) } catch (_: Exception) { null }
                    val mapJson = try { translationJobDao.getSourceTextMapJson(jobId) } catch (_: Exception) { null }
                    restoredTexts = TranslationJobEntity.deserializeTexts(textsJson)
                    restoredMap = TranslationJobEntity.deserializeTextMap(mapJson)
                }

                if (restoredTexts.isEmpty()) {
                    _uiState.update { it.copy(screenState = ScreenState.Error("无法恢复翻译任务: 源文本丢失")) }
                    return@launch
                }

                reloadGlossaryEntriesSync()
                loadActiveConfig()

                sourceTexts = restoredTexts
                sourceTextMap = restoredMap
                translatedResults = emptyList()
                sourceTextRepository.setSourceTexts(restoredMap)
                val detectedLanguages = detectLanguagesFor(restoredTexts)

                _uiState.update {
                    it.copy(
                        selectedFileName = meta.sourceFileName,
                        selectedFileUri = meta.sourceFileUri,
                        sourceLang = detectedLanguages.first,
                        targetLang = detectedLanguages.second,
                        progress = TranslationProgress(
                            totalItems = restoredTexts.size,
                            completedItems = 0,
                            currentBatch = 0,
                            totalBatches = 1,
                            status = "Ready"
                        )
                    )
                }

                pendingCancelJobId = jobId
                _uiState.update { it.copy(screenState = ScreenState.Idle) }
            } catch (e: Exception) {
                AppLogger.e(TAG, "resumeFromJob failed", e)
                _uiState.update { it.copy(screenState = ScreenState.Error("无法恢复翻译任务")) }
            }
        }
    }

    private suspend fun reloadGlossaryEntriesSync() {
        try {
            glossaryEntries = glossaryDao.getByProjectId("default_project")
        } catch (_: Exception) {
            glossaryEntries = emptyList()
        }
    }

    fun dismissResumable() {
        _uiState.update { it.copy(screenState = ScreenState.Idle) }
    }

    fun dismissTerminalDialog() {
        _uiState.update { it.copy(shouldShowTerminalDialog = false) }
    }

    // ── Export ──────────────────────────────────────

    fun onExportResult(uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val keys = sourceTextMap.keys.toList()
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Cannot open output file")

                outputStream.use { os ->
                    val writer = com.mtt.app.data.io.StreamingJsonWriter(os)
                    writer.use { w ->
                        w.writeBegin()
                        for (i in keys.indices) {
                            val key = keys[i]
                            val translated = translatedResults.getOrElse(i) { sourceTextMap[key] ?: "" }
                            w.writeEntry(key, translated)
                        }
                        w.writeEnd()
                    }
                }

                _uiState.update { it.copy(screenState = ScreenState.Completed) }
            } catch (e: Exception) {
                _uiState.update { it.copy(screenState = ScreenState.Error("Failed to export: ${e.message}")) }
            }
        }
    }

    // ── Glossary Extraction ─────────────────────────

    fun extractTerms() {
        if (useService) {
            extractTermsViaService()
        } else {
            viewModelScope.launch {
                extractTermsDirectly()
            }
        }
    }

    internal suspend fun extractTermsDirectly() {
        _uiState.update { it.copy(isExtracting = true, extractionProgress = ExtractionProgress(0, 0)) }
        val texts = sourceTextRepository.sourceTexts.value
        val srcLang = _uiState.value.sourceLang

        when (val result = extractTermsUseCase.extractTerms(texts, srcLang) { completed, total ->
            _uiState.update { it.copy(extractionProgress = ExtractionProgress(completed, total)) }
        }) {
            is com.mtt.app.core.error.Result.Success -> {
                if (result.data.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            extractedTerms = result.data,
                            showExtractionReview = true,
                            isExtracting = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            extractionMessage = "未发现候选术语",
                            isExtracting = false
                        )
                    }
                }
            }
            is com.mtt.app.core.error.Result.Failure -> {
                _uiState.update {
                    it.copy(
                        extractionMessage = "术语提取失败: ${result.exception.message}",
                        isExtracting = false
                    )
                }
            }
        }
    }

    private fun extractTermsViaService() {
        val texts = sourceTextRepository.sourceTexts.value
        if (texts.isEmpty()) {
            _uiState.update { it.copy(extractionMessage = "没有可分析的文本") }
            return
        }

        _uiState.update { it.copy(isExtracting = true, extractionProgress = ExtractionProgress(0, 0)) }

        val srcLang = _uiState.value.sourceLang

        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = ExtractionJobEntity(
            jobId = jobId,
            status = ExtractionJobEntity.STATUS_FREQUENCY_ANALYSIS,
            totalChunks = 0,
            completedChunks = 0,
            sourceLang = srcLang,
            sourceTextsJson = ExtractionJobEntity.serializeTexts(texts),
            createdAt = now,
            updatedAt = now
        )

        viewModelScope.launch {
            extractionJobDao.insert(job)
        }

        TranslationService.pendingExtractionTexts = texts
        TranslationService.pendingExtractionSourceLang = srcLang
        TranslationService.pendingExtractionJobId = jobId
        TranslationService.pendingExtractionResult = null

        val intent = Intent(context, TranslationService::class.java).apply {
            action = TranslationService.ACTION_EXTRACT_TERMS
        }
        ContextCompat.startForegroundService(context, intent)

        viewModelScope.launch {
            TranslationService.extractionProgress.collectLatest { ep ->
                _uiState.update { it.copy(extractionProgress = ep) }

                if (ep.isError) {
                    _uiState.update {
                        it.copy(
                            extractionMessage = ep.errorMessage ?: "术语提取失败",
                            isExtracting = false
                        )
                    }
                    return@collectLatest
                }

                _uiState.update { it.copy(isExtracting = ep.total > 0 && ep.completed < ep.total) }

                if (ep.total > 0 && ep.completed >= ep.total) {
                    val result = TranslationService.pendingExtractionResult
                    if (result != null && result.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                extractedTerms = result,
                                showExtractionReview = true,
                                isExtracting = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                extractionMessage = "未发现候选术语",
                                isExtracting = false
                            )
                        }
                    }
                }
            }
        }
    }

    fun confirmExtraction(selected: List<ExtractedTerm>) {
        val entities = selected.map { term ->
            val isProhibition = term.type == ExtractedTerm.TYPE_NON_TRANSLATE ||
                    term.suggestedTarget.isBlank()
            GlossaryEntryEntity(
                id = 0,
                projectId = "default_project",
                sourceTerm = term.sourceTerm,
                targetTerm = if (isProhibition) "" else term.suggestedTarget,
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT,
                info = buildInfoField(term)
            )
        }
        viewModelScope.launch {
            glossaryDao.insertAll(entities)
        }
        _uiState.update {
            it.copy(showExtractionReview = false, extractedTerms = emptyList())
        }
    }

    private fun buildInfoField(term: ExtractedTerm): String {
        val parts = mutableListOf<String>()
        if (term.category.isNotBlank()) parts.add(term.category)
        if (term.explanation.isNotBlank() && term.explanation != term.category) {
            parts.add(term.explanation)
        }
        return parts.joinToString(" | ")
    }

    fun cancelExtraction() {
        _uiState.update { it.copy(showExtractionReview = false, extractedTerms = emptyList()) }
    }

    fun clearExtractionMessage() {
        _uiState.update { it.copy(extractionMessage = null) }
    }

    // ── Companion ──────────────────────────────────

    data class AutoLoadData(
        val sourceTexts: List<String>,
        val sourceTextMap: Map<String, String>,
        val fileName: String
    )

    companion object {
        private const val TAG = "HomeVM"

        @JvmStatic
        var pendingAutoLoad: AutoLoadData? = null
    }
}
