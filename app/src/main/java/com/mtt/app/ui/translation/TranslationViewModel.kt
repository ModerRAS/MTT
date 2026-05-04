package com.mtt.app.ui.translation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    // ── Internal state ────────────────────────────

    /** Texts loaded from the source file, ready for translation. */
    private var sourceTexts: List<String> = emptyList()

    /** Accumulated translation results (indexed 1:1 with [sourceTexts]). */
    private var translatedResults: List<String> = emptyList()

    /** Currently running translation coroutine (null when idle). */
    private var translationJob: Job? = null

    /** Override-able dispatcher for file I/O. Tests can swap this for [kotlinx.coroutines.test.TestDispatcher]. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // ── Configurable translation parameters ───────

    private var modelInfo: ModelInfo = ModelInfo(
        modelId = "gpt-4",
        displayName = "GPT-4",
        contextWindow = 8192,
        provider = LlmProvider.OpenAI(apiKey = "")
    )

    private var sourceLang: String = "英语"
    private var targetLang: String = "中文"
    private var temperature: Float = 0.3f
    private var maxTokens: Int = 4096
    private var glossaryEntries: List<GlossaryEntryEntity> = emptyList()

    // ── Events ────────────────────────────────────

    /**
     * Read the file at [uri] and extract texts (one per non-empty line).
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

                sourceTexts = content.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

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
            model = modelInfo,
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
                val content = translatedResults.joinToString("\n")
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
