package com.mtt.app.ui.glossary

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.core.error.Result
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.ExtractionJobEntity
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.GlossaryEntryUiModel
import com.mtt.app.data.model.toEntity
import com.mtt.app.data.model.toUiModel
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.glossary.GlossaryEntry
import com.mtt.app.domain.glossary.GlossaryEngine
import com.mtt.app.domain.pipeline.SkipPatterns
import com.mtt.app.domain.usecase.ExtractTermsUseCase
import com.mtt.app.service.TranslationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the glossary screen.
 */
data class GlossaryUiState(
    val glossaryCount: Int = 0,
    val prohibitionCount: Int = 0,
    val previewEntries: List<GlossaryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasSourceTexts: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

/**
 * ViewModel for GlossaryScreen.
 * Handles glossary and prohibition list import, preview, and clear operations.
 */
@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val glossaryDao: GlossaryDao,
    private val sourceTextRepository: SourceTextRepository,
    private val extractTermsUseCase: ExtractTermsUseCase,
    private val secureStorage: SecureStorage,
    private val extractionJobDao: ExtractionJobDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlossaryUiState())
    val uiState: StateFlow<GlossaryUiState> = _uiState.asStateFlow()

    private val _glossaryUiEntries = MutableStateFlow<List<GlossaryEntryUiModel>>(emptyList())
    val glossaryUiEntries: StateFlow<List<GlossaryEntryUiModel>> = _glossaryUiEntries.asStateFlow()

    private val _pendingDeleteEntry = MutableStateFlow<GlossaryEntryUiModel?>(null)
    val pendingDeleteEntry: StateFlow<GlossaryEntryUiModel?> = _pendingDeleteEntry.asStateFlow()

    private val _extractedTerms = MutableStateFlow<List<ExtractedTerm>>(emptyList())
    val extractedTerms: StateFlow<List<ExtractedTerm>> = _extractedTerms.asStateFlow()

    private val _showExtractionReview = MutableStateFlow(false)
    val showExtractionReview: StateFlow<Boolean> = _showExtractionReview.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    /** Extraction progress: (completedChunks, totalChunks). Updated after each chunk. */
    private val _extractionProgress = MutableStateFlow(ExtractionProgress(0, 0))
    val extractionProgress: StateFlow<ExtractionProgress> = _extractionProgress.asStateFlow()

    // Current project ID - in a real app, this would come from a project manager
    private var currentProjectId: String = "default_project"

    init {
        loadGlossaryData()
        // Observe source texts availability from the shared repository
        viewModelScope.launch {
            sourceTextRepository.sourceTexts.collect { texts ->
                _uiState.update { it.copy(hasSourceTexts = texts.isNotEmpty()) }
            }
        }
        checkForIncompleteExtractionJob()
    }

    /**
     * On initialization, check for any incomplete extraction jobs from a previous session.
     * If found, restore source texts from the persisted job and notify the user so they can
     * re-run extraction without re-selecting the source file.
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
                    _uiState.update { it.copy(
                        errorMessage = "上次术语提取未完成，请重新提取"
                    ) }
                }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    /**
     * Load glossary and prohibition counts from database.
     */
    fun loadGlossaryData() {
        viewModelScope.launch {
            try {
                val entries = glossaryDao.getByProjectId(currentProjectId)
                val glossaryEntries = entries.map { it.toGlossaryEntry() }
                val prohibitionEntries = glossaryEntries.filter { it.target.isEmpty() }
                val glossaryOnlyEntries = glossaryEntries.filter { it.target.isNotEmpty() }

                val uiModels = entries.map { it.toUiModel() }
                _glossaryUiEntries.value = uiModels

                _uiState.update {
                    it.copy(
                        glossaryCount = glossaryOnlyEntries.size,
                        prohibitionCount = prohibitionEntries.size,
                        previewEntries = glossaryEntries.take(20)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "加载术语表失败: ${e.message}")
                }
            }
        }
    }

    /**
     * Import glossary from CSV file.
     * CSV format: "source, target" per line, no header.
     */
    fun importGlossaryFromCsv(uri: Uri, content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val entries = parseCsvContent(content)
                val entities = entries.map { entry ->
                    GlossaryEntryEntity(
                        projectId = currentProjectId,
                        sourceTerm = entry.source,
                        targetTerm = entry.target,
                        matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
                    )
                }

                glossaryDao.insertAll(entities)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "成功导入 ${entries.size} 条术语"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "导入术语表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Import prohibition list from text file.
     * One term per line.
     */
    fun importProhibitionList(uri: Uri, content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val terms = content.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val entities = terms.map { term ->
                    GlossaryEntryEntity(
                        projectId = currentProjectId,
                        sourceTerm = term,
                        targetTerm = "", // Empty target indicates prohibition
                        matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
                    )
                }

                glossaryDao.insertAll(entities)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "成功导入 ${terms.size} 条禁翻术语"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "导入禁翻表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear current project's glossary and prohibition list.
     */
    fun clearGlossary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                glossaryDao.deleteByProjectId(currentProjectId)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "已清空术语表"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "清空术语表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear success/error messages.
     */
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    /**
     * Add a new glossary entry.
     * Rejects empty sourceTerm. Detects duplicate sourceTerm and shows error.
     * Empty targetTerm creates a prohibition entry.
     */
    fun addEntry(sourceTerm: String, targetTerm: String, matchType: String, info: String = "") {
        viewModelScope.launch {
            try {
                if (sourceTerm.isBlank()) {
                    _uiState.update { it.copy(errorMessage = "原文术语不能为空") }
                    return@launch
                }

                val existing = glossaryDao.getByProjectId(currentProjectId)
                if (existing.any { it.sourceTerm == sourceTerm }) {
                    _uiState.update { it.copy(errorMessage = "该术语已存在: $sourceTerm") }
                    return@launch
                }

                val entity = GlossaryEntryEntity(
                    projectId = currentProjectId,
                    sourceTerm = sourceTerm,
                    targetTerm = targetTerm,
                    matchType = matchType,
                    info = info
                )
                glossaryDao.insertAll(listOf(entity))
                loadGlossaryData()

                val message = if (targetTerm.isEmpty()) "已添加禁翻条目" else "已添加术语"
                _uiState.update { it.copy(successMessage = message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "添加术语失败: ${e.message}") }
            }
        }
    }

    /**
     * Update an existing glossary entry.
     * Detects type change (term ↔ prohibition) and sends appropriate message.
     */
    fun updateEntry(entry: GlossaryEntryUiModel) {
        viewModelScope.launch {
            try {
                val allEntries = glossaryDao.getByProjectId(currentProjectId)
                val oldEntry = allEntries.find { it.id == entry.id }

                glossaryDao.updateEntry(entry.toEntity())
                loadGlossaryData()

                val message = when {
                    oldEntry != null && oldEntry.targetTerm.isEmpty() && entry.targetTerm.isNotEmpty() ->
                        "已将禁翻条目转为术语"
                    oldEntry != null && oldEntry.targetTerm.isNotEmpty() && entry.targetTerm.isEmpty() ->
                        "已将术语转为禁翻条目"
                    else -> "已更新术语"
                }
                _uiState.update { it.copy(successMessage = message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "更新术语失败: ${e.message}") }
            }
        }
    }

    /**
     * Delete a glossary entry by its database ID.
     */
    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            try {
                glossaryDao.deleteById(id)
                loadGlossaryData()
                _uiState.update { it.copy(successMessage = "已删除术语") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "删除术语失败: ${e.message}") }
            }
        }
    }

    /**
     * Show delete confirmation dialog for the given entry.
     */
    fun showDeleteConfirmation(entry: GlossaryEntryUiModel) {
        _pendingDeleteEntry.value = entry
    }

    /**
     * Confirm deletion of the pending entry.
     */
    fun confirmDelete() {
        val entry = _pendingDeleteEntry.value ?: return
        deleteEntry(entry.id)
        _pendingDeleteEntry.value = null
    }

    /**
     * Cancel deletion, clearing the pending entry.
     */
    fun cancelDelete() {
        _pendingDeleteEntry.value = null
    }

    // ── Glossary Extraction ──────────────────────────

    /**
     * When true (default in production), extraction runs through [TranslationService]
     * foreground service with notification and persistence. Set to false in unit tests
     * to run directly via [extractTermsDirectly].
     */
    internal var useService: Boolean = true

    /**
     * Extract terms from source texts using AI.
     *
     * When [useService] is true (production), runs through [TranslationService]
     * foreground service with persistent notification. Otherwise runs directly
     * via [extractTermsDirectly] for testability.
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
     * Run extraction directly via [ExtractTermsUseCase] — no foreground service.
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
            is Result.Success -> {
                if (result.data.isNotEmpty()) {
                    _extractedTerms.value = result.data
                    _showExtractionReview.value = true
                } else {
                    _uiState.update { it.copy(errorMessage = "未发现候选术语") }
                }
            }
            is Result.Failure -> {
                _uiState.update { it.copy(errorMessage = "术语提取失败: ${result.exception.message}") }
            }
        }
        _isExtracting.value = false
    }

    /**
     * Run glossary extraction through [TranslationService] foreground service.
     * Creates a persisted [ExtractionJobEntity] and shows progress notification.
     */
    private fun extractTermsViaService() {
        val texts = sourceTextRepository.sourceTexts.value
        if (texts.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "没有可分析的文本") }
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

                if (ep.isError) {
                    _uiState.update { it.copy(errorMessage = ep.errorMessage ?: "术语提取失败") }
                    _isExtracting.value = false
                    return@collect
                }

                _isExtracting.value = ep.total > 0 && ep.completed < ep.total

                if (ep.total > 0 && ep.completed >= ep.total) {
                    val result = TranslationService.pendingExtractionResult
                    if (result != null && result.isNotEmpty()) {
                        _extractedTerms.value = result
                        _showExtractionReview.value = true
                    } else {
                        _uiState.update { it.copy(errorMessage = "未发现候选术语") }
                    }
                    _isExtracting.value = false
                }
            }
        }
    }

    /**
     * Confirm extraction and insert selected terms into database.
     */
    fun confirmExtraction(selected: List<ExtractedTerm>) {
        // Skip entries that don't need translation (pure numbers, EV codes, etc.)
        val filtered = selected.filter { !SkipPatterns.shouldSkip(it.sourceTerm) }
        val skipped = selected.size - filtered.size
        val entities = filtered.map { term ->
            GlossaryEntryEntity(
                id = 0,
                projectId = currentProjectId,
                sourceTerm = term.sourceTerm,
                targetTerm = term.suggestedTarget,
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT,
                info = term.explanation
            )
        }
        viewModelScope.launch {
            glossaryDao.insertAll(entities)
            loadGlossaryData()
            val msg = "成功导入 ${filtered.size} 条术语" +
                if (skipped > 0) "（已跳过 $skipped 条数字/EV编码）" else ""
            _uiState.update { it.copy(successMessage = msg) }
        }
        _showExtractionReview.value = false
        _extractedTerms.value = emptyList()
    }

    /**
     * Cancel extraction review, clearing extracted terms.
     */
    fun cancelExtraction() {
        _showExtractionReview.value = false
        _extractedTerms.value = emptyList()
    }

    /**
     * Parse CSV content into glossary entries.
     * Format: "source, target" per line, no header.
     */
    private fun parseCsvContent(content: String): List<GlossaryEntry> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 2)
                if (parts.size == 2) {
                    val source = parts[0].trim()
                    val target = parts[1].trim()
                    if (source.isNotEmpty() && target.isNotEmpty()) {
                        GlossaryEntry(source = source, target = target)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
    }

    /**
     * Convert GlossaryEntryEntity to GlossaryEntry.
     */
    private fun GlossaryEntryEntity.toGlossaryEntry(): GlossaryEntry {
        return GlossaryEntry(
            source = sourceTerm,
            target = targetTerm,
            isRegex = matchType == GlossaryEntryEntity.MATCH_TYPE_REGEX,
            isCaseSensitive = matchType != GlossaryEntryEntity.MATCH_TYPE_CASE_INSENSITIVE,
            remark = info
        )
    }
}

/**
 * Extraction progress state.
 * @param completed    Number of chunks processed so far
 * @param total        Total number of chunks to process
 * @param errorMessage Non-null when extraction has failed; contains the error description
 */
data class ExtractionProgress(
    val completed: Int,
    val total: Int,
    val errorMessage: String? = null
) {
    val percentage: Float get() = if (total > 0) completed.toFloat() / total else 0f
    val isIndeterminate: Boolean get() = total == 0
    val isError: Boolean get() = errorMessage != null
}