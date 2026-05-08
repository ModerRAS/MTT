package com.mtt.app.ui.data

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.model.GlossaryEntryUiModel
import com.mtt.app.data.model.toEntity
import com.mtt.app.data.model.toUiModel
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.glossary.GlossaryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the data management screen.
 */
data class DataUiState(
    val glossaryCount: Int = 0,
    val prohibitionCount: Int = 0,
    val previewEntries: List<GlossaryEntryUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val hasSourceTexts: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

/**
 * ViewModel for DataScreen.
 * Handles glossary and prohibition list management WITHOUT extraction.
 */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val glossaryDao: GlossaryDao,
    private val sourceTextRepository: SourceTextRepository,
    private val secureStorage: SecureStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataUiState())
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()

    private val _glossaryEntries = MutableStateFlow<List<GlossaryEntryUiModel>>(emptyList())
    val glossaryEntries: StateFlow<List<GlossaryEntryUiModel>> = _glossaryEntries.asStateFlow()

    private val _prohibitionEntries = MutableStateFlow<List<GlossaryEntryUiModel>>(emptyList())
    val prohibitionEntries: StateFlow<List<GlossaryEntryUiModel>> = _prohibitionEntries.asStateFlow()

    private val _pendingDeleteEntry = MutableStateFlow<GlossaryEntryUiModel?>(null)
    val pendingDeleteEntry: StateFlow<GlossaryEntryUiModel?> = _pendingDeleteEntry.asStateFlow()

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
    }

    /**
     * Load glossary and prohibition counts from database.
     */
    fun loadGlossaryData() {
        viewModelScope.launch {
            try {
                val entries = glossaryDao.getByProjectId(currentProjectId)
                val uiModels = entries.map { it.toUiModel() }

                val glossaryOnly = uiModels.filter { it.targetTerm.isNotEmpty() }
                val prohibitionOnly = uiModels.filter { it.targetTerm.isEmpty() }

                _glossaryEntries.value = glossaryOnly
                _prohibitionEntries.value = prohibitionOnly

                _uiState.update {
                    it.copy(
                        glossaryCount = glossaryOnly.size,
                        prohibitionCount = prohibitionOnly.size,
                        previewEntries = uiModels.take(20)
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
}