package com.mtt.app.ui.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.mtt.app.core.logger.AppLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.ExtractionJobEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationJobEntity
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.service.TranslationService
import com.mtt.app.ui.glossary.ExtractionProgress
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val extractTermsUseCase: com.mtt.app.domain.usecase.ExtractTermsUseCase,
    private val translationJobDao: TranslationJobDao,
    private val extractionJobDao: ExtractionJobDao,
    private val cacheManager: CacheManager,
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

    private val _selectedFileUri = MutableStateFlow<String?>(null)
    val selectedFileUri: StateFlow<String?> = _selectedFileUri.asStateFlow()

    private val _prohibitionCount = MutableStateFlow(0)
    val prohibitionCount: StateFlow<Int> = _prohibitionCount.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    // ── Glossary extraction state ─────────────────

    private val _extractedTerms = MutableStateFlow<List<com.mtt.app.data.model.ExtractedTerm>>(emptyList())
    val extractedTerms: StateFlow<List<com.mtt.app.data.model.ExtractedTerm>> = _extractedTerms.asStateFlow()

    private val _showExtractionReview = MutableStateFlow(false)
    val showExtractionReview: StateFlow<Boolean> = _showExtractionReview.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractionProgress = MutableStateFlow(ExtractionProgress(0, 0))
    val extractionProgress: StateFlow<ExtractionProgress> = _extractionProgress.asStateFlow()

    private val _extractionMessage = MutableStateFlow<String?>(null)
    val extractionMessage: StateFlow<String?> = _extractionMessage.asStateFlow()

    init {
        loadCustomModelsFromStorage()
        loadModelFromSettings()
        loadGlossaryEntries()
        loadProhibitionCount()
        tryAutoStartTranslation()
        checkForIncompleteTranslationJob()
        checkForIncompleteExtractionJob()
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
     * Check if a pending auto-load was set by MTTApplication for debug/testing.
     * If so, load the source texts and auto-start translation.
     *
     * NOTE: This is called from init. Properties declared after the init block in
     * this class (sourceTexts, sourceTextMap, sourceLang, targetLang, glossaryEntries,
     * etc.) have NOT been initialized yet. We defer to a coroutine to ensure full init.
     */
    private fun tryAutoStartTranslation() {
        val autoData = pendingAutoLoad ?: return
        pendingAutoLoad = null

        val pendingTexts = autoData.sourceTexts
        val pendingMap = autoData.sourceTextMap
        val pendingName = autoData.fileName

        // Defer to a coroutine so ALL property initializers (including those declared
        // after the init block) have had a chance to run before we use them.
        // Note: ioDispatcher is declared after init so we can't use it here.
        // Use Default dispatcher to avoid ANR on large file loads.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                sourceTexts = pendingTexts
                sourceTextMap = pendingMap
                _selectedFileName.value = pendingName

                _progress.update {
                    it.copy(
                        totalItems = sourceTexts.size,
                        completedItems = 0,
                        status = "Ready"
                    )
                }

                // Store source texts in repository for cross-ViewModel access
                sourceTextRepository.setSourceTexts(pendingMap)

                // Note: Auto-translation on load is intentionally disabled.
                // The user should manually start translation via the UI.
                // For glossary extraction, load a file then use the extraction button.
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-start translation failed", e)
                _uiState.value = TranslationUiState.Error("自动翻译启动失败: ${e.message}")
            }
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

    /**
     * When true (default in production), translation runs through [TranslationService]
     * foreground service with notification and persistance. Set to false in unit tests
     * to run the pipeline directly via [startTranslationDirectly].
     */
    internal var useService: Boolean = true

    /**
     * When set, [startTranslationViaService] will cancel this job before creating a new one.
     * Used by [resumeFromJob] to prevent orphaned "IN_PROGRESS" jobs in the database.
     */
    private var pendingCancelJobId: String? = null

    // ── Configurable translation parameters ───────
    // Loaded from SecureStorage (configured in Settings screen)

    /** Current source language (e.g., "日语", "英语"). Read by TranslationScreen to initialize dropdown. */
    var sourceLang: String = secureStorage.getValue(SecureStorage.KEY_SOURCE_LANG) ?: "日语"
        private set

    /** Current target language (e.g., "中文", "英语"). Read by TranslationScreen to initialize dropdown. */
    var targetLang: String = secureStorage.getValue(SecureStorage.KEY_TARGET_LANG) ?: "中文"
        private set
    private var temperature: Float = 0.3f
    private var maxTokens: Int = 16384
    private var glossaryEntries: List<GlossaryEntryEntity> = emptyList()
    private var modelInfo: ModelInfo = ModelRegistry.defaultOpenAiModel

    /**
     * Load the saved model configuration from SecureStorage.
     * Falls back to ModelRegistry defaults if nothing is saved.
     * Embeds the actual API key and base URL from SecureStorage into the [LlmProvider]
     * since preset models in ModelRegistry have empty credentials.
     * Updates [_currentModel] StateFlow with the loaded model.
     */
    private fun loadModelFromSettings() {
        val openAiKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        val anthropicKey = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC)
        val openAiBaseUrl = secureStorage.getValue(SecureStorage.KEY_OPENAI_BASE_URL)
            ?: LlmProvider.OpenAI("", "").baseUrl
        val anthropicBaseUrl = secureStorage.getValue(SecureStorage.KEY_ANTHROPIC_BASE_URL)
            ?: LlmProvider.Anthropic("", "").baseUrl

        modelInfo = if (anthropicKey?.isNotBlank() == true && openAiKey?.isNotBlank() != true) {
            // Anthropic is configured (has key, OpenAI doesn't)
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_ANTHROPIC_MODEL)
                ?: ModelRegistry.defaultAnthropicModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                // Augment preset model with actual credentials
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
            // Default to OpenAI (or both configured, use OpenAI)
            val modelId = secureStorage.getApiKey(SecureStorage.KEY_OPENAI_MODEL)
                ?: ModelRegistry.defaultOpenAiModel.modelId
            val baseModel = ModelRegistry.getById(modelId)
            if (baseModel != null) {
                // Augment preset model with actual credentials
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
     * Uses [StreamingJsonReader] to avoid OOM on large files.
     * Emits [TranslationUiState.Loading] while reading, then transitions to
     * [TranslationUiState.Idle] on success or [TranslationUiState.Error] on failure.
     */
    fun onFileSelected(uri: Uri, fileName: String? = null) {
        _uiState.value = TranslationUiState.Loading("Reading file...")
        _selectedFileUri.value = uri.toString()
        if (fileName != null) _selectedFileName.value = fileName

        viewModelScope.launch(ioDispatcher) {
            try {
                val inputStream = context.contentResolver
                    .openInputStream(uri)
                    ?: throw IOException("Cannot open file")

                val map = LinkedHashMap<String, String>()
                inputStream.use { stream ->
                    val jsonReader = com.mtt.app.data.io.StreamingJsonReader(stream)
                    jsonReader.use { reader ->
                        reader.readEntries { key, value ->
                            map[key] = value
                        }
                    }
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
     * If [useService] is true (default in production), creates a persistent
     * [TranslationJobEntity] and delegates execution to [TranslationService]
     * which runs as a foreground service with notification.
     *
     * If [useService] is false (set in tests), runs the pipeline directly
     * via [startTranslationDirectly] for testability.
     */
    fun onStartTranslation() {
        if (sourceTexts.isEmpty()) {
            _uiState.value = TranslationUiState.Error("No texts to translate")
            return
        }

        if (useService) {
            startTranslationViaService()
        } else {
            startTranslationDirectly()
        }
    }

    /**
     * Run translation directly via [TranslateTextsUseCase] without the foreground service.
     * Used in unit tests where service infrastructure is unavailable.
     * Internal visibility for test access.
     */
    internal fun startTranslationDirectly() {
        // Cancel any in-flight translation
        translationJob?.cancel()

        val config = buildConfig()
        _uiState.value = TranslationUiState.Translating(_progress.value)

        translationJob = viewModelScope.launch {
            translateTexts(sourceTexts, config)
                .flowOn(ioDispatcher)
                .collect { result -> handleBatchResult(result) }
        }
    }

    // ── Source text file storage (avoids CursorWindow 2MB limit) ──

    /** Write source texts to a file in cacheDir. Used instead of storing multi-MB JSON in Room. */
    private fun writeSourceTextsToFile(jobId: String, texts: List<String>, textMap: Map<String, String>) {
        val dir = java.io.File(context.cacheDir, "translation_sources")
        dir.mkdirs()
        val file = java.io.File(dir, "$jobId.json")
        JSONObject().apply {
            put("texts", JSONArray(texts))
            put("map", JSONObject(textMap.toMap()))
        }.let { file.writeText(it.toString(), Charsets.UTF_8) }
    }

    /** Read source texts from file. Returns null if file doesn't exist. */
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

    /**
     * Run translation through [TranslationService] foreground service.
     *
     * Creates a persisted job record, sets companion shared state for the service,
     * starts the foreground service, and observes progress via the service's
     * companion [StateFlow]. Results are read from [CacheManager] on completion.
     */
    private fun startTranslationViaService() {
        translationJob?.cancel()

        // Cancel any orphaned job from a previous resume before creating a new one
        val cancelOldId = pendingCancelJobId
        pendingCancelJobId = null

        val config = buildConfig()
        val fileUri = _selectedFileUri.value ?: ""
        val fileName = _selectedFileName.value

        // Generate a fresh job ID
        val jobId = UUID.randomUUID().toString()

        // Mark old job as cancelled if this is a resume
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

        // Create a persisted job record for resumability.
        // Source texts are stored as a FILE in cacheDir (not in Room DB) to avoid
        // Android's 2MB CursorWindow limit on the large JSON blobs.
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

        // Persist job before starting service.
        // Use runBlocking to ensure the write commits before the service starts
        // and survives process death (an async viewModelScope.launch may be
        // killed before the transaction completes on force-stop).
        runBlocking(Dispatchers.IO) {
            translationJobDao.insert(job)
        }

        // Set companion shared state for the service
        TranslationService.pendingTexts = sourceTexts.toList()
        TranslationService.pendingConfig = config
        TranslationService.pendingJobId = jobId

        // Start the foreground service
        val intent = Intent(context, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)

        // Observe progress from the service's companion StateFlow
        _uiState.value = TranslationUiState.Translating(TranslationProgress.initial())
        translationJob = viewModelScope.launch {
            TranslationService.serviceProgress.collect { progress ->
                _progress.value = progress
                when (progress.status) {
                    "翻译完成" -> {
                        loadResultsFromCache(config)
                        _uiState.value = TranslationUiState.Completed
                    }
                    "翻译失败" -> {
                        _uiState.value = TranslationUiState.Error("翻译失败")
                    }
                    "已取消" -> {
                        _progress.value = TranslationProgress.initial()
                        _uiState.value = TranslationUiState.Idle
                    }
                    else -> {
                        _uiState.value = TranslationUiState.Translating(progress)
                    }
                }
            }
        }
    }

    /**
     * Build a [TranslationConfig] from current ViewModel parameters.
     */
    private fun buildConfig(): TranslationConfig {
        val safeMode = (_currentMode.value as? TranslationMode) ?: TranslationMode.TRANSLATE
        val safeModel = (_currentModel.value as? ModelInfo) ?: ModelRegistry.defaultOpenAiModel
        val safeSourceLang = (sourceLang as? String) ?: "日语"
        val safeTargetLang = (targetLang as? String) ?: "中文"
        val safeGlossary = (glossaryEntries as? List<GlossaryEntryEntity>) ?: emptyList()

        val savedBatchSize = secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE)
            ?.toIntOrNull() ?: 50
        val savedConcurrency = secureStorage.getValue(SecureStorage.KEY_CONCURRENCY)
            ?.toIntOrNull() ?: 1

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

    /**
     * After the service completes translation, read results from [CacheManager].
     * Builds [translatedResults] by mapping each [sourceTexts] entry to its cached translation.
     */
    private suspend fun loadResultsFromCache(config: TranslationConfig) {
        try {
            val projectId = _selectedFileName.value ?: CacheManager.DEFAULT_PROJECT_ID
            val cacheMap = cacheManager.exportToJson(projectId)
            translatedResults = sourceTexts.map { cacheMap[it] ?: it }
        } catch (_: Exception) {
            // If cache read fails, use source texts as fallback
            translatedResults = sourceTexts.toList()
        }
    }

    /** Cancel the current translation job and return to idle. */
    fun onPauseTranslation() {
        translationJob?.cancel()
        translationJob = null
        _progress.value = TranslationProgress.initial()
        _uiState.value = TranslationUiState.Idle

        // Also stop the foreground service if it's running
        if (useService) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = TranslationService.ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /**
     * Resume translation from the current state.
     *
     * Cache hits from a previous partial run will short-circuit repeated work
     * via [TranslateTextsUseCase]'s internal cache check.
     */
    fun onResumeTranslation() {
        onStartTranslation()
    }

    // ── Resume / Checkpoint ─────────────────────────

    /**
     * On initialization, check the database for any incomplete translation jobs.
     * If found, emit [TranslationUiState.Resumable] so the UI can offer to resume.
     *
     * This runs asynchronously and will not block ViewModel initialization.
     */
    private fun checkForIncompleteTranslationJob() {
        viewModelScope.launch {
            try {
                val terminalStatuses = listOf(
                    TranslationJobEntity.STATUS_COMPLETED,
                    TranslationJobEntity.STATUS_FAILED,
                    TranslationJobEntity.STATUS_CANCELLED
                )
                // Use lightweight summary to avoid loading multi-MB sourceTextsJson
                // which exceeds Android's 2MB CursorWindow limit.
                val summary = translationJobDao.getLatestIncompleteSummary(terminalStatuses)
                if (summary != null && summary.totalItems > 0) {
                    AppLogger.d(TAG, "checkForIncompleteTranslationJob: FOUND job ${summary.jobId}")
                    _uiState.value = TranslationUiState.Resumable(
                        jobId = summary.jobId,
                        totalItems = summary.totalItems,
                        completedItems = summary.completedItems,
                        sourceFileName = summary.sourceFileName
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "checkForIncompleteTranslationJob failed", e)
            }
        }
    }

    /**
     * On initialization, check for any incomplete glossary extraction jobs from a previous session.
     * If found, restore source texts from the persisted job and notify the user so they can
     * re-run extraction from the Glossary tab without re-selecting the source file.
     */
    private fun checkForIncompleteExtractionJob() {
        viewModelScope.launch {
            try {
                val terminalStatuses = listOf(
                    ExtractionJobEntity.STATUS_COMPLETED,
                    ExtractionJobEntity.STATUS_FAILED
                )
                val incomplete = extractionJobDao.getLatestIncomplete(terminalStatuses)
                if (incomplete != null) {
                    // Restore source texts from persisted JSON so user can re-run extraction
                    val restoredTexts = ExtractionJobEntity.deserializeTexts(incomplete.sourceTextsJson)
                    if (restoredTexts.isNotEmpty()) {
                        sourceTextRepository.setSourceTexts(restoredTexts)
                    }
                    _extractionMessage.value = "上次术语提取未完成，请重新提取"
                }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    /**
     * Resume an incomplete translation job by [jobId].
     *
     * Restores source texts from the persisted job (not from URI, which may
     * have lost permissions after process death), then delegates to
     * [onStartTranslation]. Already-cached items are skipped automatically
     * by [TranslateTextsUseCase]'s internal cache lookup.
     *
     * The old job is marked CANCELLED when [startTranslationViaService] creates
     * the new job, preventing orphaned "IN_PROGRESS" entries in the database.
     */
    fun resumeFromJob(jobId: String) {
        viewModelScope.launch {
            try {
                val meta = translationJobDao.getMetadataById(jobId) ?: return@launch

                // Read source texts from file (avoids CursorWindow 2MB limit on DB blobs)
                val fileData = readSourceTextsFromFile(jobId)
                val restoredTexts: List<String>
                val restoredMap: Map<String, String>
                if (fileData != null) {
                    restoredTexts = fileData.first
                    restoredMap = fileData.second
                } else {
                    // Fallback: try reading from DB columns (old entries before file-based storage)
                    val textsJson = try { translationJobDao.getSourceTextsJson(jobId) } catch (_: Exception) { null }
                    val mapJson = try { translationJobDao.getSourceTextMapJson(jobId) } catch (_: Exception) { null }
                    restoredTexts = TranslationJobEntity.deserializeTexts(textsJson)
                    restoredMap = TranslationJobEntity.deserializeTextMap(mapJson)
                }

                if (restoredTexts.isEmpty()) {
                    _uiState.value = TranslationUiState.Error("无法恢复翻译任务: 源文本丢失")
                    return@launch
                }

                // Re-load glossary synchronously BEFORE setting Idle state,
                // so buildConfig() has the glossary available when user clicks "开始"
                reloadGlossaryEntriesSync()
                loadModelFromSettings()

                // Set up the ViewModel state as if a file was selected
                sourceTexts = restoredTexts
                sourceTextMap = restoredMap
                _selectedFileName.value = meta.sourceFileName
                _selectedFileUri.value = meta.sourceFileUri
                translatedResults = emptyList()

                sourceTextRepository.setSourceTexts(restoredMap)

                _progress.value = TranslationProgress(
                    totalItems = restoredTexts.size,
                    completedItems = 0,
                    currentBatch = 0,
                    totalBatches = 1,
                    status = "Ready"
                )

                // Set this before setting Idle so startTranslationViaService cancels the old job
                pendingCancelJobId = jobId

                _uiState.value = TranslationUiState.Idle

                // IMPORTANT: After this, the user clicks "开始" to re-start translation.
                // Already-cached items will be skipped automatically by TranslateTextsUseCase.
            } catch (e: Exception) {
                AppLogger.e(TAG, "resumeFromJob failed", e)
                _uiState.value = TranslationUiState.Error("无法恢复翻译任务")
            }
        }
    }

    /**
     * Load glossary entries synchronously (suspend) for use immediately after resume.
     * Unlike the fire-and-forget [loadGlossaryEntries], this ensures glossary data
     * is available before the user can click "开始".
     */
    private suspend fun reloadGlossaryEntriesSync() {
        try {
            glossaryEntries = glossaryDao.getByProjectId("default_project")
        } catch (_: Exception) {
            glossaryEntries = emptyList()
        }
    }

    /**
     * Dismiss the resumable state and go back to idle,
     * allowing the user to start a fresh translation.
     */
    fun dismissResumable() {
        _uiState.value = TranslationUiState.Idle
    }

    /** Switch the active [TranslationMode] (translate / polish / proofread). */
    fun onChangeMode(mode: TranslationMode) {
        _currentMode.value = mode
    }

    /**
     * Write [translatedResults] to the file at [uri] as MTool JSON.
     *
     * Uses [StreamingJsonWriter] to avoid OOM on large exports.
     * Emits [TranslationUiState.Completed] on success.
     */
    fun onExportResult(uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val keys = sourceTextMap.keys.toList()
                val outputStream = context.contentResolver
                    .openOutputStream(uri)
                    ?: throw IOException("Cannot open output file")

                outputStream.use { os ->
                    val writer = com.mtt.app.data.io.StreamingJsonWriter(os)
                    writer.use { w ->
                        w.writeBegin()
                        for (i in keys.indices) {
                            val key = keys[i]
                            val translated = (translatedResults.getOrElse(i) { sourceTextMap[key] ?: "" })
                            w.writeEntry(key, translated)
                        }
                        w.writeEnd()
                    }
                }

                _uiState.value = TranslationUiState.Completed
            } catch (e: Exception) {
                _uiState.value = TranslationUiState.Error(
                    "Failed to export: ${e.message}"
                )
            }
        }
    }

    // ── Glossary Extraction ──────────────────────────

    /**
     * Extract terminology from loaded source texts using AI.
     *
     * When [useService] is true (production), runs through [TranslationService]
     * foreground service with persistent notification. Otherwise runs directly
     * via [extractTermsDirectly] for testability.
     *
     * Shows review dialog on success, error message on failure.
     * Reports progress via [_extractionProgress].
     */
    fun extractTerms() {
        if (useService) {
            extractTermsViaService()
        } else {
            viewModelScope.launch {
                extractTermsDirectly()
            }
        }
    }

    /**
     * Run glossary extraction directly via [ExtractTermsUseCase].
     * Used in unit tests where service infrastructure is unavailable.
     * Internal visibility for test access.
     */
    internal suspend fun extractTermsDirectly() {
        _isExtracting.value = true
        _extractionProgress.value = ExtractionProgress(0, 0)
        val texts = sourceTextRepository.sourceTexts.value
        val srcLang = secureStorage.getValue(SecureStorage.KEY_SOURCE_LANG) ?: "自动检测"

        when (val result = extractTermsUseCase.extractTerms(texts, srcLang) { completed, total ->
            _extractionProgress.value = ExtractionProgress(completed, total)
        }) {
            is com.mtt.app.core.error.Result.Success -> {
                if (result.data.isNotEmpty()) {
                    _extractedTerms.value = result.data
                    _showExtractionReview.value = true
                } else {
                    _extractionMessage.value = "未发现候选术语"
                }
            }
            is com.mtt.app.core.error.Result.Failure -> {
                _extractionMessage.value = "术语提取失败: ${result.exception.message}"
            }
        }
        _isExtracting.value = false
    }

    /**
     * Run glossary extraction through [TranslationService] foreground service.
     *
     * Creates a persisted [ExtractionJobEntity], sets companion shared state,
     * starts the service, and observes progress via the service's companion
     * StateFlows. Reads the result from [TranslationService.pendingExtractionResult]
     * on completion.
     */
    private fun extractTermsViaService() {
        val texts = sourceTextRepository.sourceTexts.value
        if (texts.isEmpty()) {
            _extractionMessage.value = "没有可分析的文本"
            return
        }

        _isExtracting.value = true
        _extractionProgress.value = ExtractionProgress(0, 0)

        val srcLang = secureStorage.getValue(SecureStorage.KEY_SOURCE_LANG) ?: "自动检测"

        // Create persisted job record with source texts for resume after process death
        val jobId = java.util.UUID.randomUUID().toString()
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

        // Set companion shared state for the service
        TranslationService.pendingExtractionTexts = texts
        TranslationService.pendingExtractionSourceLang = srcLang
        TranslationService.pendingExtractionJobId = jobId
        TranslationService.pendingExtractionResult = null

        // Start the foreground service
        val intent = Intent(context, TranslationService::class.java).apply {
            action = TranslationService.ACTION_EXTRACT_TERMS
        }
        ContextCompat.startForegroundService(context, intent)

        // Observe progress from the service
        viewModelScope.launch {
            TranslationService.extractionProgress.collect { ep ->
                _extractionProgress.value = ep

                // Handle error state
                if (ep.isError) {
                    _extractionMessage.value = ep.errorMessage ?: "术语提取失败"
                    _isExtracting.value = false
                    return@collect
                }

                // Update extracting flag while in progress
                _isExtracting.value = ep.total > 0 && ep.completed < ep.total

                // Check if extraction completed (total > 0 and completed >= total is terminal)
                if (ep.total > 0 && ep.completed >= ep.total) {
                    val result = TranslationService.pendingExtractionResult
                    if (result != null && result.isNotEmpty()) {
                        _extractedTerms.value = result
                        _showExtractionReview.value = true
                    } else {
                        _extractionMessage.value = "未发现候选术语"
                    }
                    _isExtracting.value = false
                }
            }
        }
    }

    /**
     * Confirm extraction and insert selected terms into database.
     */
    fun confirmExtraction(selected: List<com.mtt.app.data.model.ExtractedTerm>) {
        val entities = selected.map { term ->
            com.mtt.app.data.model.GlossaryEntryEntity(
                id = 0,
                projectId = "default_project",
                sourceTerm = term.sourceTerm,
                targetTerm = term.suggestedTarget,
                matchType = com.mtt.app.data.model.GlossaryEntryEntity.MATCH_TYPE_EXACT,
                info = term.explanation
            )
        }
        viewModelScope.launch {
            glossaryDao.insertAll(entities)
            // Keep Idle state after extraction — don't set Completed (that's for translation only)
        }
        _showExtractionReview.value = false
        _extractedTerms.value = emptyList()
    }

    /** Cancel extraction review, clearing extracted terms. */
    fun cancelExtraction() {
        _showExtractionReview.value = false
        _extractedTerms.value = emptyList()
    }

    /** Clear the extraction result message. Called after snackbar dismisses. */
    fun clearExtractionMessage() {
        _extractionMessage.value = null
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
                        status = "Complete",
                        totalInputTokens = it.totalInputTokens + result.inputTokens,
                        totalOutputTokens = it.totalOutputTokens + result.outputTokens,
                        totalCacheTokens = it.totalCacheTokens + result.cacheTokens
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

    /**
     * Data for debug/testing auto-load from MTTApplication.
     * Set by MTTApplication.tryAutoConfigure() before the ViewModel is created.
     */
    data class AutoLoadData(
        val sourceTexts: List<String>,
        val sourceTextMap: Map<String, String>,
        val fileName: String
    )

    companion object {
        private const val TAG = "TranslationVM"

        /**
         * Pending auto-load data set by MTTApplication from debug config.
         * Consumed once in [tryAutoStartTranslation] during init.
         */
        @JvmStatic
        var pendingAutoLoad: AutoLoadData? = null
    }
}
